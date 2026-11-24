package com.teagather.member.service;

import com.teagather.common.exception.BusinessException;
import com.teagather.member.constant.MemberRole;
import com.teagather.member.dto.SignupForm;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public Member getByUsername(String username) {
        return memberRepository.findByUsername(username)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없어요."));
    }

    public Member get(Long id) {
        return memberRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없어요."));
    }

    // 회원가입 : 오류가 있으면 BindingResult 에 담고 null
    @Transactional
    public Member signup(SignupForm form, BindingResult br) {
        if (!form.getPassword().equals(form.getPassword2())) {
            br.rejectValue("password2", "mismatch", "비밀번호가 서로 달라요.");
        }
        if (memberRepository.existsByUsername(form.getUsername().trim())) {
            br.rejectValue("username", "duplicate", "이미 쓰고 있는 아이디예요.");
        }
        String email = blankToNull(form.getEmail());
        if (email != null && memberRepository.findByEmail(email).isPresent()) {
            br.rejectValue("email", "duplicate", "이미 가입된 이메일이에요.");
        }
        if (br.hasErrors()) return null;

        Member m = Member.builder()
                .username(form.getUsername().trim())
                .password(passwordEncoder.encode(form.getPassword()))
                .name(form.getName().trim())
                .phone(blankToNull(form.getPhone()))
                .email(email)
                .marketingAgree(form.isMarketingAgree())
                .role(MemberRole.CUSTOMER)
                .created(LocalDateTime.now())
                .build();
        return memberRepository.save(m);
    }

    // 관리자 : 고객 정지 / 다시 사용
    @Transactional
    public Member toggleActive(Long id) {
        Member m = get(id);
        if (m.isAdmin()) throw new BusinessException("관리자 계정은 여기서 바꿀 수 없어요.");
        m.setActive(!m.isActive());
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
