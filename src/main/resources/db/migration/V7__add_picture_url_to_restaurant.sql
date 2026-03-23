-- V7: 给 restaurant 表添加 picture_url 字段，用于存储餐厅图片
ALTER TABLE restaurant
    ADD COLUMN picture_url text[];
