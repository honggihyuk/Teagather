package com.teagather.member.service;

import com.teagather.member.constant.MemberRole;
import com.teagather.member.entity.Member;
import com.teagather.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

// 로그인 처리 : 로그인 폼의 username 으로 회원을 찾아 UserDetails 로 돌려준다.
// 정지된 회원(active = false)은 disabled → 로그인되지 않는다.
@Service
@RequiredArgsConstructor
public class MemberSecurityService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Member member = memberRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        MemberRole role = (member.getRole() == null) ? MemberRole.CUSTOMER : member.getRole();

        return User.withUsername(member.getUsername())
                .password(member.getPassword())
                .authorities(List.of(new SimpleGrantedAuthority(role.getValue())))
                .disabled(!member.isActive())
                .build();
    }
}
