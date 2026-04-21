package com.hmdp.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopCacheInvalidationMessage {
    private Long shopId;
    private Long issuedAt;
}
