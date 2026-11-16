package com.egg.order;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 批次号：{@code SG} + 收购日期 + {@code -} + 当日序号，序号每日从 1 起（v7 5.5）。作废的单也不复用号。 */
@Service
public class BatchService {

    private final BatchSeqMapper mapper;

    public BatchService(BatchSeqMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public String next(LocalDate date) {
        mapper.bump(date);
        return BatchNo.of(date, mapper.last(date));
    }
}
