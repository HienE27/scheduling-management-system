package com.hospital.scheduler.controller;

import com.hospital.scheduler.config.AuthCookieProperties;
import com.hospital.scheduler.dto.ApiResponse;
import com.hospital.scheduler.dto.AuthResponse;
import com.hospital.scheduler.dto.LoginRequest;
import com.hospital.scheduler.dto.RefreshTokenRequest;
import com.hospital.scheduler.dto.request.BootstrapAdminRequest;
import com.hospital.scheduler.dto.request.ChangePasswordRequest;
import com.hospital.scheduler.service.AuthService;
import com.hospital.scheduler.service.BootstrapAdminService;
import com.hospital.scheduler.service.BootstrapPermissionsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication APIs")
public class AuthController {

    private static final String AUTH_COOKIE_NAME = "medschedule_access_token";
    private static final String REFRESH_COOKIE_NAME = "medschedule_refresh_token";
    private static final String AUTH_TOKEN_HEADER = "X-Auth-Token";

    private final AuthService authService;
    private final AuthCookieProperties authCookieProperties;
    private final BootstrapAdminService bootstrapAdminService;
    private final BootstrapPermissionsService bootstrapPermissionsService;

    /**
     * One-time bootstrap endpoint for fresh production databases.
     *
     * <p>Creates the very first admin user (with ADMIN + MANAGER roles) when
     * the {@code staff} table is empty. Returns 403 once any user exists.</p>
     *
     * <p>This is a public, no-auth endpoint by design — production databases
     * start empty and need a way to bootstrap the first admin without going
     * through DataSeeder (which would insert ~400 rows and exceed Render's
     * 5-minute port-scan window).</p>
     */
    @PostMapping("/bootstrap-admin")
    @Operation(
            summary = "Bootstrap first admin user (fresh DB only)",
            description = "Tạo admin đầu tiên khi database còn trống. Endpoint tự vô hiệu " +
                    "hóa sau khi đã có ít nhất 1 user. Dùng để seed production DB mà không cần DataSeeder."
    )
    public ResponseEntity<ApiResponse<String>> bootstrapAdmin(
            @Valid @RequestBody BootstrapAdminRequest request) {
        var admin = bootstrapAdminService.bootstrapAdmin(request);
        return ResponseEntity.ok(ApiResponse.success(
                admin.getUsername(),
                "Admin '" + admin.getUsername() + "' đã được tạo. Bây giờ có thể login."
        ));
    }

    /**
     * One-time endpoint to seed the permission catalog + role-permission matrix.
     *
     * <p>Returns 403 if the {@code role_permission} table is not empty (to
     * protect an existing production RBAC matrix from being clobbered).</p>
     */
    @PostMapping("/bootstrap-permissions")
    @Operation(
            summary = "Bootstrap permission catalog (fresh DB only)",
            description = "Tạo permission catalog + role-permission matrix khi database thiếu. " +
                    "Endpoint tự vô hiệu hóa sau khi role_permission đã có data."
    )
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> bootstrapPermissions() {
        var result = bootstrapPermissionsService.bootstrapPermissions();
        return ResponseEntity.ok(ApiResponse.success(
                result,
                "Permission catalog + role-permission matrix đã được seed."
        ));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Login",
            description = "Authenticate user and return JWT access token + refresh token"
    )
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        AuthResponse authResponse = authService.login(request, httpRequest);
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildAuthCookie(AUTH_COOKIE_NAME, authResponse.getToken(),
                        authResponse.getExpiresIn()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildAuthCookie(REFRESH_COOKIE_NAME, authResponse.getRefreshToken(),
                        authResponse.getRefreshExpiresIn()).toString());
        response.setHeader(AUTH_TOKEN_HEADER, authResponse.getToken());
        return ResponseEntity.ok(ApiResponse.success(authResponse, "Login successful"));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Refresh access token",
            description = "Exchange a valid refresh token for a fresh access+refresh pair. " +
                    "Old refresh token is revoked (rotation). Reuse of a revoked token " +
                    "revokes ALL refresh tokens for that staff (theft signal)."
    )
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            @Valid @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        // Accept refresh token from cookie OR from request body — body takes precedence.
        String rawRefresh = body != null && body.getRefreshToken() != null
                ? body.getRefreshToken()
                : refreshCookie;
        AuthResponse auth = authService.refresh(rawRefresh, httpRequest);
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildAuthCookie(AUTH_COOKIE_NAME, auth.getToken(), auth.getExpiresIn()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildAuthCookie(REFRESH_COOKIE_NAME, auth.getRefreshToken(),
                        auth.getRefreshExpiresIn()).toString());
        return ResponseEntity.ok(ApiResponse.success(auth, "Token refreshed"));
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Logout",
            description = "Revoke the refresh token (if any) and clear auth cookies"
    )
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            @RequestBody(required = false) RefreshTokenRequest body,
            HttpServletResponse response) {
        String rawRefresh = body != null && body.getRefreshToken() != null
                ? body.getRefreshToken()
                : refreshCookie;
        authService.logout(rawRefresh);

        response.addHeader(HttpHeaders.SET_COOKIE, buildExpiredAuthCookie(AUTH_COOKIE_NAME).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildExpiredAuthCookie(REFRESH_COOKIE_NAME).toString());
        return ResponseEntity.ok(ApiResponse.success(null, "Logout successful"));
    }

    /**
     * Self-service password rotation. Verifies the supplied {@code currentPassword}
     * before accepting {@code newPassword} — matches the login flow so a probe
     * can't distinguish "wrong old password" from "unknown user". The active
     * access token remains valid (no forced re-auth).
     */
    @PostMapping("/change-password")
    @Operation(
            summary = "Đổi mật khẩu (self-service)",
            description = "Đổi mật khẩu của chính người dùng hiện tại. Cần xác nhận mật khẩu hiện tại. " +
                    "Access token hiện tại vẫn dùng được — không bắt buộc đăng nhập lại."
    )
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ResponseEntity.ok(ApiResponse.success(null, "Đổi mật khẩu thành công"));
    }

    private ResponseCookie buildAuthCookie(String name, String token, long expiresIn) {
        return ResponseCookie.from(name, token)
                .httpOnly(true)
                .secure(authCookieProperties.cookieSecure())
                .sameSite(authCookieProperties.cookieSameSite())
                .path("/")
                .maxAge(expiresIn / 1000)
                .build();
    }

    private ResponseCookie buildExpiredAuthCookie(String name) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(authCookieProperties.cookieSecure())
                .sameSite(authCookieProperties.cookieSameSite())
                .path("/")
                .maxAge(0)
                .build();
    }
}
