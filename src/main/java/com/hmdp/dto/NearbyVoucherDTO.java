package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NearbyVoucherDTO {
    private Long shopId;
    private String shopName;
    private Double distance;

    private Long voucherId;
    private String title;
    private Long payValue;
    private Long actualValue;
    private Integer stock;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private Boolean available;
}

