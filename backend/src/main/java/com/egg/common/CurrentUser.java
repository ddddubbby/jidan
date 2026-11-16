package com.egg.common;

/** 当前登录人，由登录拦截器按 token 查出。控制器方法直接声明这个类型的参数即可拿到。 */
public record CurrentUser(long id, String name, String phone, Role role) {
}
