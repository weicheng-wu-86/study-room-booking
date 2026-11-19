package com.studyroom.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页结果，只暴露前端需要的 total 和 records。
 */
public record PageResult<T>(long total, List<T> records) {

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getRecords());
    }
}
