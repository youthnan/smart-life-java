package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoucherOrderServiceImpl.class);

    @Resource
    private RedisWorker redisWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private ApplicationContext applicationContext;

    @Value("${hmdp.seckill.stream.consumer-count:1}")
    private int consumerCount;
    @Value("${hmdp.seckill.stream.read-batch-size:1}")
    private int readBatchSize;
    @Value("${hmdp.seckill.stream.pending-error-alert-threshold:10}")
    private int pendingErrorAlertThreshold;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private final Counter orderProcessedCounter;
    private final Counter orderConsumeErrorCounter;
    private final Counter pendingRetryCounter;
    private final Counter pendingErrorCounter;

    public VoucherOrderServiceImpl(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        this.orderProcessedCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.stream.orders.processed").register(meterRegistry);
        this.orderConsumeErrorCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.stream.orders.consume_error").register(meterRegistry);
        this.pendingRetryCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.stream.orders.pending_retry").register(meterRegistry);
        this.pendingErrorCounter = meterRegistry == null ? null
                : Counter.builder("hmdp.stream.orders.pending_error").register(meterRegistry);
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    private ExecutorService orderConsumerExecutor;

    private volatile boolean orderConsumerActive = true;
    private static final AtomicInteger CONSUMER_SEQ = new AtomicInteger(0);

    @PostConstruct
    private void init(){
        int threads = Math.max(1, consumerCount);
        orderConsumerExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "seckill-order-stream-consumer-" + CONSUMER_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < threads; i++) {
            String consumerName = RedisConstants.ORDER_CONSUMER_NAME + "-" + (i + 1);
            orderConsumerExecutor.submit(new VoucherOrderHandler(consumerName));
        }

        try {
            stringRedisTemplate.opsForStream().createGroup(RedisConstants.ORDER_STREAM_KEY, RedisConstants.ORDER_CONSUMER_GROUP);
            LOGGER.info("Redis Stream consumer group {} created", RedisConstants.ORDER_CONSUMER_GROUP);
        } catch (Exception e) {
            LOGGER.warn("Redis Stream consumer group {} exists or create failed: {}", RedisConstants.ORDER_CONSUMER_GROUP, e.getMessage());
        }
    }

    @PreDestroy
    public void stopOrderConsumer() {
        orderConsumerActive = false;
        if (orderConsumerExecutor != null) {
            orderConsumerExecutor.shutdownNow();
        }
    }

    private static boolean isRedisOrContextClosed(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException) {
                String msg = t.getMessage();
                if (msg != null && msg.contains("destroyed")) {
                    return true;
                }
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    /** Redis 开启了 requirepass 但未配置客户端密码时会出现，重试无意义。 */
    private static boolean isRedisNoAuth(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("NOAUTH")) {
                return true;
            }
        }
        return false;
    }

    private class VoucherOrderHandler implements Runnable {
        private final String consumerName;

        private VoucherOrderHandler(String consumerName) {
            this.consumerName = consumerName;
        }

        @Override
        public void run() {
            int pendingFailures = 0;
            while (orderConsumerActive && !Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(RedisConstants.ORDER_CONSUMER_GROUP, consumerName),
                            StreamReadOptions.empty().count(Math.max(1, readBatchSize)).block(Duration.ofSeconds(2)),
                            StreamOffset.create(RedisConstants.ORDER_STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    for (MapRecord<String, Object, Object> record : list) {
                        Map<Object, Object> value = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                        handleVoucherOrder(voucherOrder);
                        stringRedisTemplate.opsForStream().acknowledge(RedisConstants.ORDER_STREAM_KEY, RedisConstants.ORDER_CONSUMER_GROUP, record.getId());
                        increment(orderProcessedCounter);
                    }
                    pendingFailures = 0;
                } catch (Exception e) {
                    if (!orderConsumerActive || isRedisOrContextClosed(e)) {
                        LOGGER.info("Seckill order consumer stopped: {}", e.toString());
                        break;
                    }
                    if (isRedisNoAuth(e)) {
                        LOGGER.warn("Redis 返回 NOAUTH：请在环境变量 SPRING_REDIS_PASSWORD 或 spring.redis.password 中配置与 Redis requirepass 一致的密码。已停止秒杀 Stream 消费者。");
                        orderConsumerActive = false;
                        break;
                    }
                    increment(orderConsumeErrorCounter);
                    LOGGER.error("处理订单异常", e);
                    pendingFailures = handlePendingList(pendingFailures);
                }
            }
        }

        private int handlePendingList(int pendingFailures) {
            while (orderConsumerActive && !Thread.currentThread().isInterrupted()) {
                try {
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(RedisConstants.ORDER_CONSUMER_GROUP, consumerName),
                            StreamReadOptions.empty().count(Math.max(1, readBatchSize)),
                            StreamOffset.create(RedisConstants.ORDER_STREAM_KEY, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        return 0;
                    }
                    for (MapRecord<String, Object, Object> record : list) {
                        Map<Object, Object> value = record.getValue();
                        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                        handleVoucherOrder(voucherOrder);
                        stringRedisTemplate.opsForStream().acknowledge(RedisConstants.ORDER_STREAM_KEY, RedisConstants.ORDER_CONSUMER_GROUP, record.getId());
                        increment(pendingRetryCounter);
                    }
                } catch (Exception e) {
                    if (!orderConsumerActive || isRedisOrContextClosed(e)) {
                        LOGGER.info("Pending order handler stopped: {}", e.toString());
                        return pendingFailures;
                    }
                    if (isRedisNoAuth(e)) {
                        LOGGER.warn("Redis NOAUTH，pending 补偿已停止（请配置 Redis 密码后重启应用）。");
                        orderConsumerActive = false;
                        return pendingFailures;
                    }
                    increment(pendingErrorCounter);
                    pendingFailures++;
                    if (pendingFailures >= Math.max(1, pendingErrorAlertThreshold)) {
                        LOGGER.error("pending list handling keeps failing (count={}), please inspect stream backlog and DB status",
                                pendingFailures, e);
                    } else {
                        LOGGER.error("处理pending订单异常", e);
                    }
                    try{
                        Thread.sleep(1000);
                    }catch(InterruptedException ie){
                        Thread.currentThread().interrupt();
                        return pendingFailures;
                    }
                }
            }
            return pendingFailures;
        }
    }



   private void handleVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            LOGGER.error("不允许重复下单");
            return;
        }
        try {
            applicationContext.getBean(IVoucherOrderService.class).createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
   }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisWorker.nextID("order");
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
                );

        int r = result.intValue();
        if(r!=0) {
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        LOGGER.info("seckill request accepted, voucherId={}, userId={}, orderId={}", voucherId, userId, orderId);
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userId = voucherOrder.getUserId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            LOGGER.warn("duplicate order blocked, userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return;
        }
        //库存充足，扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update(); //where id = ? and stock > 0
        if (!success) {
            LOGGER.warn("stock not enough when create order, userId={}, voucherId={}", userId, voucherOrder.getVoucherId());
            return;
        }
        try {
            save(voucherOrder);
        } catch (DuplicateKeyException e) {
            // 已扣减库存但唯一约束冲突：本事务未回滚（异常被吞），需回补库存以免少卖
            seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .update();
            LOGGER.debug("duplicate order row ignored (uk_user_voucher), stock compensated, userId={}, voucherId={}",
                    userId, voucherOrder.getVoucherId());
            return;
        }
        LOGGER.info("voucher order created, orderId={}, userId={}, voucherId={}",
                voucherOrder.getId(), userId, voucherOrder.getVoucherId());

    }
}

