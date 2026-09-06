package com.hospital.scheduler.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/v1/auth/bootstrap-admin}.
 *
 * <p>Used to create the very first admin account on a fresh production DB
 * (Render + Neon PostgreSQL). The endpoint is gated by a server-side check
 * that only allows invocation when the {@code staff} table is empty, so it
 * is safe to expose publicly — after the first admin exists, the endpoint
 * refuses further requests with 403.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BootstrapAdminRequest {

    @NotBlank(message = "Username không được để trống")
    @Size(min = 3, max = 50, message = "Username phải từ 3 đến 50 ký tự")
    private String username;

    @NotBlank(message = "Password không được để trống")
    @Size(min = 6, max = 100, message = "Password phải từ 6 đến 100 ký tự")
    private String password;

    @NotBlank(message = "Họ tên không được để trống")
    @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
    private String fullName;

    private String email;
    private String phone;
}
