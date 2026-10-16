package shipment;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 送货批次数据模型。
 * 对应 shipments.csv 的一行；字段解析容错，非法/缺失值置为 null。
 */
public record Shipment(
        String id,
        Instant checkInTime,   // 供应商车辆到达园区登记入库时间(带 +08:00)
        Instant lastSyncTime,  // 预约依据的最后同步时间(UTC, 带 Z)
        String factoryZone,    // 接收园区
        String materialGroup,  // 物料大类
        Integer quotaRemaining,// 预约时剩余可用收货配额
        Integer batchVolume,   // 申报包装箱数量
        Integer acceptStatus   // 1=合格入库, 0=拒收退回, null=缺失
) {

    /** 解析带时区偏移的 ISO-8601 时间(支持 +08:00 与 Z)，统一转为 Instant(UTC 瞬间)。 */
    public static Instant parseInstant(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static Integer parseInt(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 同步陈旧度：从最后同步到实际到场的间隔(小时)。时区已归一化，可直接相减。 */
    public Double stalenessHours() {
        if (checkInTime == null || lastSyncTime == null) {
            return null;
        }
        return (checkInTime.toEpochMilli() - lastSyncTime.toEpochMilli()) / 3_600_000.0;
    }

    /** 超额比 = 申报箱数 / 剩余配额；配额为 0 或 null 时视为极度超额(返回 +∞)。 */
    public double overRatio() {
        if (quotaRemaining == null || quotaRemaining <= 0 || batchVolume == null) {
            return Double.POSITIVE_INFINITY;
        }
        return (double) batchVolume / quotaRemaining;
    }

    /** 是否为有效的验收结论样本(状态为 0/1)。 */
    public boolean hasValidStatus() {
        return acceptStatus != null && (acceptStatus == 0 || acceptStatus == 1);
    }

    public boolean isRejected() {
        return acceptStatus != null && acceptStatus == 0;
    }
}
