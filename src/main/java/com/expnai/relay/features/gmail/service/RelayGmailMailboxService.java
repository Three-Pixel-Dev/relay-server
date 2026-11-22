package com.expnai.relay.features.gmail.service;

import java.util.List;

import com.expnai.relay.common.web.ApiResponse;
import com.expnai.relay.features.gmail.dto.response.RelayGmailAuthUrlResponse;
import com.expnai.relay.features.gmail.dto.response.RelayGmailMailboxResponse;

public interface RelayGmailMailboxService {

	ApiResponse<List<RelayGmailMailboxResponse>> listMine();

	ApiResponse<RelayGmailAuthUrlResponse> startConnect();

	ApiResponse<RelayGmailMailboxResponse> completeConnect(String code, String state);

	ApiResponse<Void> disconnect(Long mailboxId);

	ApiResponse<RelayGmailMailboxResponse> get(Long mailboxId);
}
