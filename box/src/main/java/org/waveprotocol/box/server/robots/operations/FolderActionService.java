/**
 * Copyright 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.waveprotocol.box.server.robots.operations;

import com.google.common.collect.Maps;
import com.google.wave.api.InvalidRequestException;
import com.google.wave.api.JsonRpcConstant.ParamsProperty;
import com.google.wave.api.OperationRequest;

import org.waveprotocol.box.server.robots.OperationContext;
import org.waveprotocol.box.server.robots.util.OperationUtil;
import org.waveprotocol.wave.model.conversation.ConversationBlip;
import org.waveprotocol.wave.model.conversation.ConversationView;
import org.waveprotocol.wave.model.conversation.ObservableConversation;
import org.waveprotocol.wave.model.id.IdConstants;
import org.waveprotocol.wave.model.id.IdUtil;
import org.waveprotocol.wave.model.id.InvalidIdException;
import org.waveprotocol.wave.model.id.ModernIdSerialiser;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.supplement.PrimitiveSupplement;
import org.waveprotocol.wave.model.supplement.SupplementedWave;
import org.waveprotocol.wave.model.supplement.SupplementedWaveImpl;
import org.waveprotocol.wave.model.supplement.SupplementedWaveImpl.DefaultFollow;
import org.waveprotocol.wave.model.supplement.WaveletBasedSupplement;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.opbased.OpBasedWavelet;

import java.util.Map;

/**
 * Implements the "robot.folderAction" operations.
 * 
 * @author yurize@apache.org (Yuri Zelikov)
 */
public class FolderActionService implements OperationService {

  public enum ModifyHowType {
    MARK_AS_READ("markAsRead"), MARK_AS_UNREAD("markAsUnread");

    private final String value;

    private ModifyHowType(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  public static FolderActionService create() {
    return new FolderActionService();
  }

  @Override
  public void execute(OperationRequest operation, OperationContext context,
      ParticipantId participant) throws InvalidRequestException {

    String modifyHow = OperationUtil.getRequiredParameter(operation, ParamsProperty.MODIFY_HOW);
    String blipId = OperationUtil.getOptionalParameter(operation, ParamsProperty.BLIP_ID);

    SupplementedWave supplement = buildSupplement(operation, context, participant);

    if (modifyHow.equals(ModifyHowType.MARK_AS_READ.getValue())) {
      if (blipId == null || blipId.isEmpty()) {
        supplement.markAsRead();
      } else {
        ObservableConversation conversation =
            context.openConversation(operation, participant).getRoot();
        ConversationBlip blip = conversation.getBlip(blipId);
        supplement.markAsRead(blip);
      }
    } else if (modifyHow.equals(ModifyHowType.MARK_AS_UNREAD.getValue())) {
      supplement.markAsUnread();
    } else {
      throw new UnsupportedOperationException("Unsupported folder action: " + modifyHow);
    }
    // Construct empty response.
    Map<ParamsProperty, Object> data = Maps.newHashMap();
    context.constructResponse(operation, data);
  }

  /**
   * Builds the supplement model for a wave.
   * 
   * @param operation the operation.
   * @param context the operation context.
   * @param participant the viewer.
   * @return the wave supplement.
   * @throws InvalidRequestException if the wave id provided in the operation is invalid.
   */
  private SupplementedWave buildSupplement(OperationRequest operation, OperationContext context,
      ParticipantId participant) throws InvalidRequestException {
    OpBasedWavelet wavelet = context.openWavelet(operation, participant);
    ConversationView conversationView = context.getConversationUtil().buildConversation(wavelet);

    // TODO (Yuri Z.) Find a way to obtain an instance of IdGenerator and use it to create udwId.
    WaveletId udwId =
        WaveletId.of(participant.getDomain(),
            IdUtil.join(IdConstants.USER_DATA_WAVELET_PREFIX, participant.getAddress()));
    String waveIdStr = OperationUtil.getRequiredParameter(operation, ParamsProperty.WAVE_ID);
    WaveId waveId = null;
    try {
      waveId = ModernIdSerialiser.INSTANCE.deserialiseWaveId(waveIdStr);
    } catch (InvalidIdException e) {
      throw new InvalidRequestException("Invalid WAVE_ID parameter: " + waveIdStr, operation, e);
    }
    OpBasedWavelet udw = context.openWavelet(waveId, udwId, participant);

    PrimitiveSupplement udwState = WaveletBasedSupplement.create(udw);

    SupplementedWave supplement =
        SupplementedWaveImpl.create(udwState, conversationView, participant, DefaultFollow.ALWAYS);
    return supplement;
  }
}
