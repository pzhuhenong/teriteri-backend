package com.teriteri.backend.service.impl.user;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.teriteri.backend.mapper.UserMapper;
import com.teriteri.backend.pojo.CustomResponse;
import com.teriteri.backend.pojo.User;
import com.teriteri.backend.service.impl.UserDetailsImpl;
import com.teriteri.backend.service.user.UserAccountService;
import com.teriteri.backend.utils.JwtUtil;
import com.teriteri.backend.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class UserAccountServiceImpl implements UserAccountService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuthenticationProvider authenticationProvider;

    /**
     * 用户注册
     * @param username 账号
     * @param password 密码
     * @param confirmedPassword 确认密码
     * @return CustomResponse对象
     */
    @Override
    public CustomResponse register(String username, String password, String confirmedPassword) {
        CustomResponse customResponse = new CustomResponse();
        if (username == null) {
            customResponse.setCode(403);
            customResponse.setMessage("账号不能为空");
            return customResponse;
        }
        if (password == null || confirmedPassword == null) {
            customResponse.setCode(403);
            customResponse.setMessage("密码不能为空");
            return customResponse;
        }
        username = username.trim();   //删掉用户名的空白符
        if (username.length() == 0) {
            customResponse.setCode(403);
            customResponse.setMessage("账号不能为空");
            return customResponse;
        }
        if (username.length() > 50) {
            customResponse.setCode(403);
            customResponse.setMessage("账号长度不能大于50");
            return customResponse;
        }
        if (password.length() == 0 || confirmedPassword.length() == 0 ) {
            customResponse.setCode(403);
            customResponse.setMessage("密码不能为空");
            return customResponse;
        }
        if (password.length() > 50 || confirmedPassword.length() > 50 ) {
            customResponse.setCode(403);
            customResponse.setMessage("密码长度不能大于50");
            return customResponse;
        }
        if (!password.equals(confirmedPassword)) {
            customResponse.setCode(403);
            customResponse.setMessage("两次输入的密码不一致");
            return customResponse;
        }

        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("username", username);
        User user = userMapper.selectOne(queryWrapper);   //查询数据库里值等于username的数据
        if (user != null) {
            customResponse.setCode(403);
            customResponse.setMessage("账号已存在");
            return customResponse;
        }

        QueryWrapper<User> queryWrapper1 = new QueryWrapper<>();
        queryWrapper1.orderByDesc("uid").last("limit 1");    // 降序选第一个
        User last_user = userMapper.selectOne(queryWrapper1);
        int new_user_uid;
        if (last_user == null) {
            new_user_uid = 1;
        } else {
            new_user_uid = last_user.getUid() + 1;
        }
        String encodedPassword = passwordEncoder.encode(password);  // 密文存储
        String avatar_url = "https://cube.elemecdn.com/9/c2/f0ee8a3c7c9638a54940382568c9dpng.png";
        Date now = new Date();
        User new_user = new User(
                null,
                username,
                encodedPassword,
                "用户_" + new_user_uid,
                avatar_url,
                "这个人很懒，什么都没留下~",
                0,
                0,
                0,
                now,
                null
        );
        userMapper.insert(new_user);

        customResponse.setMessage("注册成功！欢迎加入T站");
        return customResponse;
    }

    /**
     * 用户登录
     * @param username 账号
     * @param password 密码
     * @return CustomResponse对象
     */
    @Override
    public CustomResponse login(String username, String password) {
        //验证是否能正常登录
        //将用户名和密码封装成一个类，这个类不会存明文了，将是加密后的字符串
        UsernamePasswordAuthenticationToken authenticationToken =
                new UsernamePasswordAuthenticationToken(username, password);

        // 用户名或密码错误会直接抛出异常
        Authentication authenticate = authenticationProvider.authenticate(authenticationToken);

        //将用户取出来
        UserDetailsImpl loginUser = (UserDetailsImpl) authenticate.getPrincipal();
        User user = loginUser.getUser();

        // 顺便更新redis中的数据
        redisUtil.setExObjectValue("user:" + user.getUid(), user);  // 默认存活1小时

        CustomResponse customResponse = new CustomResponse();

        // 检查账号状态，1 表示封禁中，不允许登录
        if (user.getState() == 1) {
            customResponse.setCode(403);
            customResponse.setMessage("账号异常，封禁中");
            return customResponse;
        }

        //将uid封装成一个jwttoken，同时token也会被缓存到redis中
        String token = jwtUtil.createToken(user.getUid().toString(), "user");

        try {
            // 把完整的用户信息存入redis，时间跟token一样，注意单位
            // 这里缓存的user信息建议只供读取uid用，其中的状态等非静态数据可能不准，所以 redis另外存值
            redisUtil.setExObjectValue("security:user:" + user.getUid(), user, 60L * 60 * 24 * 2, TimeUnit.SECONDS);
            // 将该用户放到redis中在线集合
            redisUtil.addMember("login_member", user.getUid());
        } catch (Exception e) {
            log.error("存储redis数据失败");
            throw e;
        }

        // 每次登录顺便返回user信息，就省去再次发送一次获取用户个人信息的请求
        Map<String, Object> map = new HashMap<>();
        map.put("uid", user.getUid());
        map.put("nickname", user.getNickname());
        map.put("avatar_url", user.getAvatar());
        map.put("description", user.getDescription());
        map.put("exp", user.getExp());
        map.put("state", user.getState());

        Map<String, Object> final_map = new HashMap<>();
        final_map.put("token", token);
        final_map.put("user", map);
        customResponse.setMessage("登录成功");
        customResponse.setData(final_map);
        return customResponse;
    }

    /**
     * 获取用户个人信息
     * @return CustomResponse对象
     */
    @Override
    public CustomResponse personalInfo() {
        UsernamePasswordAuthenticationToken authenticationToken =
                (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        UserDetailsImpl loginUser = (UserDetailsImpl) authenticationToken.getPrincipal();
        User suser = loginUser.getUser();    // 这里的user是登录时存的security:user，因为是静态数据，可能会跟实际的有区别，所以只能用作获取uid用

        // 从redis中获取最新数据
        User user = redisUtil.getObject("user:" + suser.getUid(), User.class);

        // 如果redis中没有user数据，就从mysql中获取并更新到redis
        if (user == null) {
            user = userMapper.selectById(suser.getUid());
            redisUtil.setExObjectValue("user:" + user.getUid(), user);  // 默认存活1小时
        }

        CustomResponse customResponse = new CustomResponse();
        // 检查账号状态，1 表示封禁中，不允许登录
        if (user.getState() == 1) {
            customResponse.setCode(403);
            customResponse.setMessage("账号异常，封禁中");
            return customResponse;
        }

        Map<String, Object> map = new HashMap<>();
        map.put("uid", user.getUid());
        map.put("nickname", user.getNickname());
        map.put("avatar_url", user.getAvatar());
        map.put("description", user.getDescription());
        map.put("exp", user.getExp());
        map.put("state", user.getState());

        customResponse.setData(map);
        return customResponse;
    }

    /**
     * 退出登录，清空redis中相关用户登录认证
     */
    @Override
    public void logout() {
        UsernamePasswordAuthenticationToken authenticationToken =
                (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        UserDetailsImpl loginUser = (UserDetailsImpl) authenticationToken.getPrincipal();
        User suser = loginUser.getUser();

        // 清除redis中该用户的登录认证数据，不需要抛异常，有没有删掉都没关系，系统会轮询处理过期登录
        redisUtil.delValue("token:user:" + suser.getUid());
        redisUtil.delValue("security:user:" + suser.getUid());
        redisUtil.delMember("login_member", suser.getUid());
    }
}