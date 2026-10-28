package com.book.eval.controller;

import com.book.eval.mapper.UserMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * 用户认证接口：注册 / 登录 / 登出 / 会话信息
 * 登录态用 HttpSession 保存，密码采用 SHA-256 加盐哈希存储
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserMapper userMapper;

    public AuthController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 注册：查重 → 加盐哈希入库 → 自动登录 */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> resp = new HashMap<>();
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        String nickname = body.getOrDefault("nickname", "").trim();

        if (username.isEmpty() || password.isEmpty()) {
            resp.put("ok", false);
            resp.put("message", "用户名和密码不能为空");
            return resp;
        }
        if (password.length() < 6) {
            resp.put("ok", false);
            resp.put("message", "密码至少 6 位");
            return resp;
        }
        if (userMapper.countByUsername(username) > 0) {
            resp.put("ok", false);
            resp.put("message", "用户名已被注册");
            return resp;
        }

        String salt = randomSalt();
        userMapper.insert(username, hash(salt, password), salt, nickname.isEmpty() ? username : nickname);

        // 注册成功自动登录
        Map<String, Object> u = userMapper.selectByUsername(username);
        loginSession(session, u);
        resp.put("ok", true);
        resp.put("user", publicUser(u));
        return resp;
    }

    /** 登录：校验加盐哈希 */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpSession session) {
        Map<String, Object> resp = new HashMap<>();
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");

        Map<String, Object> u = username.isEmpty() ? null : userMapper.selectByUsername(username);
        if (u == null || !hash(String.valueOf(u.get("salt")), password)
                .equals(String.valueOf(u.get("password_hash")))) {
            resp.put("ok", false);
            resp.put("message", "用户名或密码错误");
            return resp;
        }

        loginSession(session, u);
        resp.put("ok", true);
        resp.put("user", publicUser(u));
        return resp;
    }

    /** 登出 */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return Map.of("ok", true);
    }

    /** 当前登录态（未登录返回 ok=false） */
    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        Map<String, Object> resp = new HashMap<>();
        Object uid = session.getAttribute("userId");
        if (uid == null) {
            resp.put("ok", false);
            resp.put("user", null);
            return resp;
        }
        resp.put("ok", true);
        resp.put("user", Map.of(
                "id", uid,
                "username", session.getAttribute("username"),
                "nickname", session.getAttribute("nickname")
        ));
        return resp;
    }

    // ---------- 内部辅助 ----------

    private void loginSession(HttpSession session, Map<String, Object> u) {
        session.setAttribute("userId", ((Number) u.get("id")).longValue());
        session.setAttribute("username", String.valueOf(u.get("username")));
        session.setAttribute("nickname", String.valueOf(u.get("nickname")));
    }

    private Map<String, Object> publicUser(Map<String, Object> u) {
        return Map.of(
                "id", ((Number) u.get("id")).longValue(),
                "username", String.valueOf(u.get("username")),
                "nickname", String.valueOf(u.get("nickname"))
        );
    }

    /** SHA-256(salt + ":" + password) 十六进制 */
    private static String hash(String salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("密码哈希失败", e);
        }
    }

    /** 16 位随机盐 */
    private static String randomSalt() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
