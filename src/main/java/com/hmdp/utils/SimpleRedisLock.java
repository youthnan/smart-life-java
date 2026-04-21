package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private  static  final String KEY_PREFIX="lock";
    private  static  final String ID_PREFIX= UUID.randomUUID()+"-";
    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(Long timeoutSec){
        //获取线程
        String  threadId = ID_PREFIX+Thread.currentThread().getId();

        //获取锁
        String key = KEY_PREFIX+name;
        Boolean success =  stringRedisTemplate.opsForValue().setIfAbsent(key,threadId+"",timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

//    @Override
//    public void unlock() {
//        String  threadId = ID_PREFIX+Thread.currentThread().getId();
//        String key = KEY_PREFIX+name;
//        String id = stringRedisTemplate.opsForValue().get(key);
//        if(threadId.equals(id)){
//            stringRedisTemplate.delete(key);
//        }
//    }


    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT; //释放锁的脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));//设置脚本的位置，在classpathRourse 里面，名字叫unlock.lua
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),//单元素集合
                ID_PREFIX + Thread.currentThread().getId());//传的线程标识
    }
}
