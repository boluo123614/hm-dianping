package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 秒杀订单服务：请求线程负责 Redis 资格判断，后台线程负责 MySQL 下单。
 * 调用链：seckillVoucher → Lua 写入 stream.orders → VoucherOrderHandler → handleVoucherOrder → createVoucherOrder → ACK。
 * 请求返回订单 ID 时只表示任务已入队，数据库订单可能尚未创建完成。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private ApplicationContext applicationContext;

    private static final String ORDER_STREAM_KEY = "stream.orders";
    private static final String ORDER_GROUP = "g1";
    // 对应课程的单实例消费者；多实例部署时需要使用不同消费者名，并接管故障消费者的待确认消息。
    private static final String ORDER_CONSUMER = "c1";

    // 配置可复用的 Lua 脚本对象；这里指定脚本位置和返回类型，调用 execute 时才执行脚本。
    // 返回码约定：0 = 取得购买资格，1 = 库存不足，2 = 重复购买。
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    // 每个应用实例使用一个后台工作线程，避免请求线程等待数据库写入。
    private final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 应用准备完成时，Spring 事务代理已经创建。先取得代理、初始化消费组，再启动消费线程。
    // 这样即使还没有新的秒杀请求，也能通过代理处理 Redis 中遗留的订单消息。
    @EventListener(ApplicationReadyEvent.class)
    public void initVoucherOrderHandler() {
        this.proxy = applicationContext.getBean(IVoucherOrderService.class);
        initOrderStreamGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void initOrderStreamGroup() {
        try {
            // 等价于 XGROUP CREATE stream.orders g1 0 MKSTREAM。
            // 0 表示从已有消息的开头消费；true（MKSTREAM）让队列不存在时自动创建空 Stream。
            stringRedisTemplate.execute((RedisCallback<String>) connection ->
                    connection.xGroupCreate(ORDER_STREAM_KEY.getBytes(StandardCharsets.UTF_8),
                            ORDER_GROUP, ReadOffset.from("0"), true));
        } catch (Exception e) {
            // 应用重启时消费组通常已存在，只忽略 BUSYGROUP；连接失败等其他错误仍然抛出。
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
                    return;
                }
            }
            throw e;
        }
    }

    @PreDestroy
    private void stopVoucherOrderHandler() {
        // 关闭应用时中断后台线程；尚未 ACK 的消息仍留在 Redis，供下次启动重试。
        SECKILL_ORDER_EXECUTOR.shutdownNow();
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 先处理 c1 的待确认消息：既恢复上次运行遗留的任务，也重试本次失败的任务。
                    handlePendingList();
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    // 每次最多取一条新消息；没有消息时最多阻塞 2 秒，避免不断空转。
                    List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                            Consumer.from(ORDER_GROUP, ORDER_CONSUMER),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    // 等待超时也可能返回 null 或空集合，此时继续下一轮读取。
                    if (records == null || records.isEmpty()) {
                        continue;
                    }
                    handleStreamRecord(records.get(0));
                } catch (Exception e) {
                    // 不 ACK：消息保留在 pending-list，下一轮先重试它，不会自动恢复 Redis 库存。
                    log.error("处理订单异常，消息保留在待确认列表中", e);
                    try {
                        // 数据库或 Redis 故障时稍后重试，避免同一条失败消息立即反复处理。
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        private void handlePendingList() {
            while (!Thread.currentThread().isInterrupted()) {
                // XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                // 0 读取当前消费者 c1 已收到但未 ACK 的消息，不读取其他消费者的 pending-list。
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(ORDER_GROUP, ORDER_CONSUMER),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.from("0"))
                );
                if (records == null || records.isEmpty()) {
                    return; // 待确认消息处理完毕，回到 run 中读取新消息。
                }
                // 失败时异常交给 run 记录并延迟；下次仍从 pending-list 读取这条消息。
                handleStreamRecord(records.get(0));
            }
        }

        private void handleStreamRecord(MapRecord<String, Object, Object> record) {
            // Lua 写入的 id/userId/voucherId 是字符串，Hutool 会转换成订单对象中的 Long 字段。
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
            handleVoucherOrder(voucherOrder);
            // 事务代理正常返回后才确认：XACK stream.orders g1 消息ID。
            // 这里用的是 Stream 消息 ID，不是业务订单 ID；ACK 只移除 pending 记录，不删除消息。
            stringRedisTemplate.opsForStream().acknowledge(ORDER_STREAM_KEY, ORDER_GROUP, record.getId());
        }
    }

//    // 连接请求线程（生产者）和后台线程（消费者）的线程安全队列，容量为 1,048,576 个任务。
//    // 任务保存在当前 JVM 内存中；它不是 Redis 队列，应用退出后尚未处理的任务会丢失。
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    // Runnable 描述后台线程要执行的工作，run 方法中的循环持续消费订单任务。
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    // take 会取出并移除一笔任务；队列为空时阻塞等待，不会不断空转占用 CPU。
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    // 当前已在后台线程中：获取用户锁，再通过事务代理执行数据库下单。
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    // 记录异常后继续循环；若下单处理抛异常，任务已出队，本实现不会自动重试或恢复 Redis 库存。
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // UserHolder 使用 ThreadLocal，后台线程读不到请求线程的登录用户，必须从订单对象获取用户 ID。
        Long userId = voucherOrder.getUserId();
        // 按用户加锁：多个应用实例处理同一用户的任务时，协调数据库端的“查重复 + 下单”。
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 不等待获取锁；未指定租期，成功后由 Redisson 看门狗负责续期。
        boolean isLock = lock.tryLock();
        // 锁竞争失败只说明当前锁被占用，不一定说明数据库已经存在重复订单。
        if (!isLock) {
            // 抛异常让消费线程稍后重试；直接 return 会使调用方误以为处理成功并 ACK。
            throw new IllegalStateException("用户订单锁被占用，稍后重试");
        }
        try {
            // 通过 Spring 代理调用，才能触发 @Transactional；直接调用 this.createVoucherOrder 会绕过代理。
            // 代理完成事务提交或回滚后才返回/抛异常，然后进入 finally，避免在事务结束前释放锁。
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 仅获取锁成功后才进入这里；无论下单正常结束还是抛异常，都尝试释放锁。
            lock.unlock();
        }
    }

    // 应用准备完成时取得 Spring 事务代理，再启动消费线程，供后台线程调用事务方法。
    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        long orderId = redisIdWorker.nextId("order");
        // Lua 把库存判断、重复判断、Redis 预扣库存和登记用户放在一次原子执行中。
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                // 本脚本根据 ARGV 拼接 Redis key，因此这里不传 KEYS 参数。
                Collections.emptyList(),
                // 依次对应 Lua 的 ARGV[1]（优惠券 ID）、ARGV[2]（用户 ID）、ARGV[3]（订单 ID）。
                voucherId.toString(), UserHolder.getUser().getId().toString(), String.valueOf(orderId)
        );
        if (result == null) {
            throw new IllegalStateException("秒杀脚本没有返回结果");
        }
        // 将脚本返回的 Long 转成 int，便于按返回码选择处理分支。
        int r = result.intValue();
        // 只有返回 0 才能继续；没有资格的请求直接结束，不进入数据库下单流程。
        if (r != 0) {
            // 1 表示库存不足，2 表示用户已取得过该优惠券的购买资格。
            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单！");
        }
        // Lua 已将订单消息写入 Stream；代理在应用启动时准备好，无需在请求中再次获取。
        // 返回的是“资格通过、任务已提交”；后台可能尚未完成 MySQL 下单。
        return Result.ok(orderId);
    }

    /**
     * 请求线程执行的入口：取得购买资格、提交订单任务，然后返回订单 ID。
     * MySQL 写入由后台线程完成，本方法不等待数据库下单结束。
     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // Lua 把库存判断、重复判断、Redis 预扣库存和登记用户放在一次原子执行中。
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                // 本脚本根据 ARGV 拼接 Redis key，因此这里不传 KEYS 参数。
//                Collections.emptyList(),
//                // 按顺序对应 Lua 的 ARGV[1]（优惠券 ID）和 ARGV[2]（用户 ID）。
//                voucherId.toString(), UserHolder.getUser().getId().toString()
//        );
//        // 将脚本返回的 Long 转成 int，便于按返回码选择处理分支。
//        int r = result.intValue();
//        // 只有返回 0 才能继续；没有资格的请求直接结束，不进入数据库下单流程。
//        if (r != 0) {
//            // 1 表示库存不足，2 表示用户已取得过该优惠券的购买资格。
//            return Result.fail(r == 1 ? "库存不足！" : "不能重复下单！");
//        }
//        // 此时 Redis 库存已经预扣，用户也已被登记；封装后台创建订单需要的三个核心字段。
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 用时间信息和 Redis 递增序号生成订单 ID；"order" 用来区分业务计数器，生成 ID 不等于保存订单。
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setVoucherId(voucherId);
//        // 必须给成员变量赋值，并且先准备代理再入队，避免后台线程取到任务时 proxy 仍为 null。
//        this.proxy = (IVoucherOrderService) AopContext.currentProxy();
//        // add 成功后，后台线程就可以取到任务；队列满时 add 抛异常，不会阻塞等待空位。
//        // 此时 Redis 已修改，入队失败不会自动撤销预扣库存和用户登记。
//        orderTasks.add(voucherOrder);
//        // 返回的是“资格通过、任务已提交”；后台可能尚未完成 MySQL 下单。
//        return Result.ok(orderId);
//    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        if (seckillVoucher == null) {
//            return Result.fail("秒杀优惠券不存在！");
//        }
//        // 2.判断秒杀是否开始
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始！");
//        }
//        // 3.判断秒杀是否结束
//        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断库存是否充足
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //尝试获取锁
//        boolean isLock = lock.tryLock();
//        //判断是否获取锁成功
//        if (!isLock) {
//            return Result.fail("服务器繁忙，请稍后再试！");
//        }
//        try {
//            //获取代理对象（事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//    }

    /**
     * 后台线程通过代理执行数据库下单；事务覆盖数据库查重、扣库存和保存订单。
     * 抛出符合回滚规则的异常时回滚数据库操作，但不会回滚先前 Lua 对 Redis 的修改。
     * 方法正常 return 不会触发事务回滚，也不会改变已经返回给用户的接口结果。
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // Redis 已做入口资格判断，数据库端再查同用户、同优惠券的订单，防止重复入库。
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            // 数据库提交后 ACK 可能失败；再次消费时发现已有订单，直接返回，避免重复扣库存。
            log.debug("订单已存在，跳过重复下单，用户ID：" + userId + "，优惠券ID：" + voucherOrder.getVoucherId());
            return;
        }
        // Redis 库存用于预留资格，MySQL 库存用于实际下单：这里扣的是数据库中的另一份库存。
        // 在同一条 UPDATE 中同时判断 stock > 0 和执行 stock = stock - 1，防止数据库库存被扣成负数。
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            // 此消息已经预留 Redis 库存；数据库扣减失败不能被当作处理成功并 ACK。
            throw new IllegalStateException("数据库库存扣减失败，请检查库存一致性");
        }
        // 保存 Lua 消息携带的订单，沿用之前返回给用户的订单 ID，不再生成新的 ID。
        if (!save(voucherOrder)) {
            // RuntimeException 触发事务回滚，撤销本次数据库库存扣减，消息保留等待重试。
            throw new IllegalStateException("订单保存失败");
        }
    }
}
