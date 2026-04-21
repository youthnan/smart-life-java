-- 为 tb_voucher_order 增加业务唯一索引：同一用户同一券仅允许一条订单记录。
-- 执行前请确认：SELECT user_id, voucher_id, COUNT(*) c FROM tb_voucher_order GROUP BY user_id, voucher_id HAVING c > 1;
-- 若存在重复行，需先清洗/合并后再执行本脚本，否则 ALTER 会失败。

ALTER TABLE `tb_voucher_order`
  ADD UNIQUE INDEX `uk_user_voucher` (`user_id`, `voucher_id`) USING BTREE;
