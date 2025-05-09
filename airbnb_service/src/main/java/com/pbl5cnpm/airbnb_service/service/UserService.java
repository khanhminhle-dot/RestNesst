package com.pbl5cnpm.airbnb_service.service;

import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.pbl5cnpm.airbnb_service.dto.Request.PasswordChangeRequest;
import com.pbl5cnpm.airbnb_service.dto.Request.UserProfileRequset;
import com.pbl5cnpm.airbnb_service.dto.Request.UserRequest;
import com.pbl5cnpm.airbnb_service.dto.Response.ListingFavorite;
import com.pbl5cnpm.airbnb_service.dto.Response.UserFavoriteResponse;
import com.pbl5cnpm.airbnb_service.dto.Response.UserInfor;
import com.pbl5cnpm.airbnb_service.dto.Response.UserResponse;
import com.pbl5cnpm.airbnb_service.entity.ListingEntity;
import com.pbl5cnpm.airbnb_service.entity.RoleEntity;
import com.pbl5cnpm.airbnb_service.entity.UserEntity;
import com.pbl5cnpm.airbnb_service.enums.RoleName;
import com.pbl5cnpm.airbnb_service.exception.AppException;
import com.pbl5cnpm.airbnb_service.exception.ErrorCode;
import com.pbl5cnpm.airbnb_service.mapper.ListingMapper;
import com.pbl5cnpm.airbnb_service.mapper.UserMapper;
import com.pbl5cnpm.airbnb_service.repository.RoleRepository;
import com.pbl5cnpm.airbnb_service.repository.UserRepository;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserMapper mapper;
    private final MailerService mailerService;
    private final PasswordEncoder passwordEncoder;
    private final ListingMapper listingMapper;
    private final CloudinaryService cloudinaryService;
    @Value("${security.secret}")
    private String SIGNER_KEY;
    @Value("${image.customer}")
    private String THUMNAIL;

    public UserResponse handleCreateUser(UserRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new AppException(ErrorCode.USERNAME_EXISTED);
        }

        new Thread(() -> {
            try {
                String name = request.getFullname();
                mailerService.sendHtmlEmail(request.getEmail(), "Welcome to Airbnb",
                        "Thank " + name + " for choosing our service!");
            } catch (MessagingException e) {
                log.error("Send mail fail!");
                e.printStackTrace();
            }
        }).start();

        String encodedPassword = passwordEncoder.encode(request.getPassword());

        Set<RoleEntity> roles = new HashSet<>();
        roles.add(roleRepository.findByRoleName(RoleName.GUEST.name())
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_EXISTED)));

        UserEntity userEntity = mapper.toUserEntity(request);
        userEntity.setPassword(encodedPassword);
        userEntity.setRoles(roles);
        userEntity.setThumnailUrl(THUMNAIL);
        return mapper.toUserResponse(userRepository.save(userEntity));
    }

    public List<UserResponse> handleGetAll() {
        List<UserEntity> users = userRepository.findAll();
        return users.stream()
                .map(mapper::toUserResponse)
                .collect(Collectors.toList());
    }

    public String getUserNameJwt(String token) throws ParseException, JOSEException {
        JWSVerifier jwsVerifier = new MACVerifier(SIGNER_KEY);
        SignedJWT jwt = SignedJWT.parse(token);
        boolean verifed = jwt.verify(jwsVerifier);
        if (!verifed) {
            return null;
        } else {
            return jwt.getJWTClaimsSet().getSubject();
        }
    }

    public UserInfor handleInfor(String token) throws ParseException, JOSEException {
        String username = getUserNameJwt(token);
        UserEntity user = this.userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        return this.mapper.toUserInfor(user);
    }

    public UserFavoriteResponse getFavorites(String username) {
        UserEntity user = this.userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        Long userId = user.getId();
        List<ListingEntity> favoriteEntity = this.userRepository.findFavorites(userId);
        List<ListingFavorite> favorites = favoriteEntity.stream()
                .map(data -> this.listingMapper.toLitingFavorite(data))
                .toList();
        return UserFavoriteResponse.builder()
                .userId(userId)
                .favorites(favorites)
                .build();
    }

    public UserInfor handleUpdateProfile(UserProfileRequset profileRequset, String username) {
        UserEntity userEntity = this.userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_EXISTED));
        if (!profileRequset.getEmail().isBlank()) {
            userEntity.setEmail(profileRequset.getEmail());
        }
        if (!profileRequset.getFullname().isBlank()) {
            userEntity.setFullname(profileRequset.getFullname());
        }
        if (!profileRequset.getPhone().isBlank()) {
            userEntity.setPhone(profileRequset.getPhone());
        }
        if (profileRequset.getThumnail() != null) {
            var url = this.cloudinaryService.uploadImageCloddy(profileRequset.getThumnail());
            userEntity.setThumnailUrl(url);
        }
        return this.mapper.toUserInfor(this.userRepository.save(userEntity));
    }

    public Long handleCountUser() {
        return this.userRepository.count();
    }

    public UserInfor handleChangePass(PasswordChangeRequest request) {
        String username = request.getUsername();
        UserEntity userEntity = this.userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USERNAME_VALID)); 

        String oldPassword = request.getPassword();
        if (!this.passwordEncoder.matches(oldPassword, userEntity.getPassword())) {
            throw new AppException(ErrorCode.PASSWORD_INVALID);
        }

        if (!request.getNewPassword().equals(request.getVerifyPassword())) {
            throw new AppException(ErrorCode.PASSWORD_CONFIRM_NOT_MATCH);
        }

        userEntity.setPassword(this.passwordEncoder.encode(request.getNewPassword()));
        return this.mapper.toUserInfor(this.userRepository.save(userEntity));
    }

}
