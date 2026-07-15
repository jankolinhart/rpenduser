package com.reelypops.rpenduser.device;

import java.util.UUID;

/** Per-user device tally for the admin dashboard's device column (one entry per user with >= 1 device). */
public record DeviceCount(UUID userId, long count) {
}
