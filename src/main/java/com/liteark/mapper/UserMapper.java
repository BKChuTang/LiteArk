package com.liteark.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liteark.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
