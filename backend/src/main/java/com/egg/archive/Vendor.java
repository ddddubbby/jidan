package com.egg.archive;

import java.time.LocalDateTime;

/** 好生意供应商，即农户。号码在 {@link VendorPhone}。 */
public class Vendor {
    public Long id;
    public String hsCode;
    public String name;
    public LocalDateTime syncedAt;
}
