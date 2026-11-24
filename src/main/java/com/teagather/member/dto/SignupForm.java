package com.teagather.member.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

// 회원가입 폼
@Getter
@Setter
public class SignupForm {

    @NotBlank(message = "아이디를 입력해 주세요.")
    @Size(min = 3, max = 30, message = "아이디는 3~30자예요.")
    @Pattern(regexp = "[A-Za-z0-9_.-]+", message = "아이디는 영문·숫자·_ . - 만 쓸 수 있어요.")
    private String username;

    @NotBlank(message = "비밀번호를 입력해 주세요.")
    @Size(min = 4, max = 60, message = "비밀번호는 4자 이상이에요.")
    private String password;

    @NotBlank(message = "비밀번호를 한 번 더 입력해 주세요.")
    private String password2;

    @NotBlank(message = "이름을 입력해 주세요.")
    @Size(max = 20, message = "이름은 20자까지예요.")
    private String name;

    @Size(max = 30)
    private String phone;

    @Email(message = "이메일 형식이 아니에요.")
    private String email;

    private boolean marketingAgree;
}
