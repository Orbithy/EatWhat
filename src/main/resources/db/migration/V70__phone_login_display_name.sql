-- 对外展示用昵称：可中文、不唯一。nick_name 保留为内部唯一登录名（旧账号的用户名、手机号注册账号的随机句柄）。
-- 为空时展示 nick_name，旧账号无需回填。
ALTER TABLE public.users ADD COLUMN display_name varchar(32);
