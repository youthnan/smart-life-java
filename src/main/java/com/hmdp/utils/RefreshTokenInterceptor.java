package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate= stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object object){

        //1、获取请求投中的token
        String token=request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }
        //2、基于Token进行查询
        String key = RedisConstants.LOGIN_USER_KEY+token;
        Map<Object,Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        //3、判断是否存在
        if(userMap.isEmpty()){
            response.setStatus(401);
            return true;
        }
        //4、不存在，拦截，新建
        //UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
        // 使用 ignoreError 确保类型转换不匹配时不会抛出异常中断流程
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(),
                CopyOptions.create()
                        .setIgnoreError(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //5、存在 保存线程
        UserHolder.saveUser(userDTO);
        //6、刷新时间
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);
        //7、放行
//       HttpSession session = request.getSession();
//       Object  user =session.getAttribute("user");
//       if(user==null){
//           response.setStatus(401);
//           return false;
//       }
//
//       UserHolder.saveUser((UserDTO) user);


        return true;

    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,Exception ex){
        UserHolder.removeUser();
    }
}
