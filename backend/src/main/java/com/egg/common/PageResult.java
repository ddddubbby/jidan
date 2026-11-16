package com.egg.common;

import java.util.List;

/** 分页结果。page 从 1 开始。 */
public record PageResult<T>(List<T> items, long total, int page, int pageSize) {
}
