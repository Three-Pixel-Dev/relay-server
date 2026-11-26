package com.expnai.relay.features.auth.service;

import java.util.List;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.auth.dto.request.RelayClientInfo;
import com.expnai.relay.features.auth.dto.request.RelayForgotPasswordRequest;
import com.expnai.relay.features.auth.dto.request.RelayGoogleLoginRequest;
import com.expnai.relay.features.auth.dto.request.RelayLoginRequest;
import com.expnai.relay.features.auth.dto.request.RelayRefreshTokenRequest;
import com.expnai.relay.features.auth.dto.request.RelayResetPasswordRequest;
import com.expnai.relay.features.auth.dto.request.RelaySetPasswordRequest;
import com.expnai.relay.features.auth.dto.request.RelaySignupRequest;
import com.expnai.relay.features.auth.dto.request.RelayUpdateProfileRequest;
import com.expnai.relay.features.auth.dto.response.RelayAuthMessageResponse;
import com.expnai.relay.features.auth.dto.response.RelayLoginResponse;
import com.expnai.relay.features.auth.dto.response.RelayMeResponse;
import com.expnai.relay.features.auth.dto.response.RelaySessionResponse;

public interface RelayAuthService {

	ApiResponse<RelayLoginResponse> signup(RelaySignupRequest request, RelayClientInfo clientInfo);

	ApiResponse<RelayLoginResponse> login(RelayLoginRequest request, RelayClientInfo clientInfo);

	ApiResponse<RelayLoginResponse> loginWithGoogle(RelayGoogleLoginRequest request, RelayClientInfo clientInfo);

	ApiResponse<RelayLoginResponse> refreshToken(RelayRefreshTokenRequest request, RelayClientInfo clientInfo);

	ApiResponse<RelayMeResponse> me();

	ApiResponse<RelayMeResponse> updateProfile(RelayUpdateProfileRequest request);

	ApiResponse<RelayMeResponse> setPassword(RelaySetPasswordRequest request);

	ApiResponse<RelayAuthMessageResponse> forgotPassword(RelayForgotPasswordRequest request);

	ApiResponse<RelayAuthMessageResponse> resetPassword(RelayResetPasswordRequest request);

	ApiResponse<List<RelaySessionResponse>> listSessions();

	ApiResponse<RelayAuthMessageResponse> revokeSession(Long sessionId);

	ApiResponse<RelayAuthMessageResponse> logout();

	ApiResponse<RelayAuthMessageResponse> logoutAll();
}
