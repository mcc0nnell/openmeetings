/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.mediaserver;

import static java.util.UUID.randomUUID;
import static org.apache.openmeetings.mediaserver.KurentoHandler.TAG_ROOM;
import static org.apache.openmeetings.mediaserver.KurentoHandler.TAG_STREAM_UID;
import static org.apache.openmeetings.util.OmFileHelper.getRecUri;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.apache.openmeetings.core.sip.ISipCallbacks;
import org.apache.openmeetings.core.sip.SipManager;
import org.apache.openmeetings.core.sip.SipStackProcessor;
import org.apache.openmeetings.db.dao.file.FileItemDao;
import org.apache.openmeetings.db.entity.file.BaseFileItem;
import org.apache.openmeetings.db.entity.room.Room;
import org.kurento.client.Continuation;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaType;
import org.kurento.client.PlayerEndpoint;
import org.kurento.client.RtpEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONObject;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Bridges whiteboard player audio into the room SIP conference without making
 * the browser media stream authoritative for whiteboard playback.
 */
@Singleton
@Named
public class WbAudioProcessor {
	private static final Logger log = LoggerFactory.getLogger(WbAudioProcessor.class);
	private static final String STREAM_PREFIX = "wb-audio-";
	private static final String PARAM_PAUSED = "paused";
	private static final String PARAM_POS = "pos";
	private static final String PARAM_UPDATED = "updated";

	@Inject
	private KurentoHandler kHandler;
	@Inject
	private SipManager sipManager;
	@Inject
	private FileItemDao fileDao;

	private final Map<Long, RoomState> rooms = new ConcurrentHashMap<>();

	public void updateVideo(Long roomId, long wbId, int slide, String uid, long fileId, JSONObject status) {
		if (roomId == null || wbId < 0 || slide < 0 || uid == null || uid.isBlank() || fileId < 1 || status == null) {
			return;
		}
		BaseFileItem item = fileDao.getAny(fileId);
		if (item == null || (BaseFileItem.Type.VIDEO != item.getType() && BaseFileItem.Type.RECORDING != item.getType())) {
			return;
		}
		File file = item.getFile();
		if (file == null || !file.isFile()) {
			log.warn("Whiteboard SIP audio source is unavailable, room {}, file {}", roomId, fileId);
			return;
		}

		RoomState roomState = rooms.computeIfAbsent(roomId, id -> new RoomState());
		WbAudioBridge bridge = roomState.bridges.computeIfAbsent(uid, id -> new WbAudioBridge(roomId, uid));
		bridge.update(wbId, slide, file, status);
		if (roomState.sipCount > 0 && roomState.room != null) {
			bridge.start(roomState.room);
		}
	}

	public void removeVideo(Long roomId, String uid) {
		RoomState roomState = rooms.get(roomId);
		if (roomState == null || uid == null) {
			return;
		}
		WbAudioBridge bridge = roomState.bridges.remove(uid);
		if (bridge != null) {
			bridge.releaseMedia();
		}
	}

	public void clearBoard(Long roomId, long wbId) {
		RoomState roomState = rooms.get(roomId);
		if (roomState == null) {
			return;
		}
		roomState.bridges.forEach((uid, bridge) -> {
			if (bridge.wbId == wbId && roomState.bridges.remove(uid, bridge)) {
				bridge.releaseMedia();
			}
		});
	}

	public void clearSlide(Long roomId, long wbId, int slide) {
		RoomState roomState = rooms.get(roomId);
		if (roomState == null) {
			return;
		}
		roomState.bridges.forEach((uid, bridge) -> {
			if (bridge.wbId == wbId && bridge.slide == slide && roomState.bridges.remove(uid, bridge)) {
				bridge.releaseMedia();
			}
		});
	}

	public void updateSipCount(Room room, long count) {
		if (room == null || room.getId() == null) {
			return;
		}
		RoomState roomState = rooms.computeIfAbsent(room.getId(), id -> new RoomState());
		roomState.room = room;
		roomState.sipCount = count;
		if (count > 0) {
			roomState.bridges.values().forEach(bridge -> bridge.start(room));
		} else {
			roomState.bridges.values().forEach(WbAudioBridge::releaseMedia);
		}
	}

	public void clearRoom(Long roomId) {
		RoomState roomState = rooms.remove(roomId);
		if (roomState != null) {
			roomState.bridges.values().forEach(WbAudioBridge::releaseMedia);
			roomState.bridges.clear();
		}
	}

	static long positionMillis(JSONObject status, long now) {
		double seconds = Math.max(0., status.optDouble(PARAM_POS, 0.));
		boolean paused = status.optBoolean(PARAM_PAUSED, true);
		long updated = status.optLong(PARAM_UPDATED, now);
		if (!paused && now > updated) {
			seconds += (now - updated) / 1000.;
		}
		return Math.max(0L, Math.round(seconds * 1000.));
	}

	private static final class RoomState {
		private final Map<String, WbAudioBridge> bridges = new ConcurrentHashMap<>();
		private volatile Room room;
		private volatile long sipCount;
	}

	private final class WbAudioBridge implements ISipCallbacks {
		private final Long roomId;
		private final String uid;
		private long wbId = -1;
		private int slide = -1;
		private File file;
		private JSONObject status = new JSONObject().put(PARAM_PAUSED, true).put(PARAM_POS, 0.);
		private Room room;
		private MediaPipeline pipeline;
		private PlayerEndpoint player;
		private RtpEndpoint rtpEndpoint;
		private Optional<SipStackProcessor> sipProcessor = Optional.empty();
		private boolean starting;
		private boolean negotiated;
		private long generation;

		private WbAudioBridge(Long roomId, String uid) {
			this.roomId = roomId;
			this.uid = uid;
		}

		private synchronized void update(long wbId, int slide, File file, JSONObject status) {
			if (this.file != null && !this.file.equals(file)) {
				releaseMedia();
			}
			this.wbId = wbId;
			this.slide = slide;
			this.file = file;
			this.status = new JSONObject(status.toString());
			if (negotiated) {
				applyState();
			}
		}

		private synchronized void start(Room room) {
			this.room = room;
			if (status.optBoolean(PARAM_PAUSED, true)) {
				if (negotiated) {
					applyState();
				}
				return;
			}
			if (negotiated) {
				applyState();
				return;
			}
			if (starting || pipeline != null || file == null || !kHandler.isConnected()) {
				return;
			}

			starting = true;
			long expectedGeneration = ++generation;
			File source = file;
			pipeline = kHandler.createPipiline(
					Map.of(TAG_ROOM, String.valueOf(roomId), TAG_STREAM_UID, STREAM_PREFIX + uid)
					, new Continuation<Void>() {
						@Override
						public void onSuccess(Void result) {
							onPipelineReady(expectedGeneration, source);
						}

						@Override
						public void onError(Throwable cause) {
							onPipelineError(expectedGeneration, cause);
						}
					});
		}

		private synchronized void onPipelineReady(long expectedGeneration, File source) {
			if (expectedGeneration != generation || pipeline == null || file == null || !file.equals(source)) {
				return;
			}
			try {
				player = new PlayerEndpoint.Builder(pipeline, getRecUri(source)).build();
				sipProcessor = sipManager.createSipStackProcessor(
						STREAM_PREFIX + randomUUID(), room, this);
				if (sipProcessor.isEmpty()) {
					log.warn("Unable to create SIP processor for whiteboard audio in room {}", roomId);
					releaseMedia();
					return;
				}
				sipProcessor.get().register();
			} catch (Exception e) {
				log.warn("Unable to create whiteboard SIP audio bridge in room {}", roomId, e);
				releaseMedia();
			}
		}

		private synchronized void onPipelineError(long expectedGeneration, Throwable cause) {
			if (expectedGeneration != generation) {
				return;
			}
			log.warn("Unable to create whiteboard audio pipeline in room {}", roomId, cause);
			releaseMedia();
		}

		@Override
		public synchronized void onRegisterOk() {
			if (pipeline == null || player == null || sipProcessor.isEmpty() || room == null) {
				return;
			}
			try {
				rtpEndpoint = new RtpEndpoint.Builder(pipeline).build();
				player.connect(rtpEndpoint, MediaType.AUDIO);
				sipProcessor.get().invite(room, null);
			} catch (Exception e) {
				log.warn("Unable to connect whiteboard audio to SIP in room {}", roomId, e);
				releaseMedia();
			}
		}

		@Override
		public void onInviteOk(String sdp, Consumer<String> answerConsumer) {
			String answer;
			synchronized (this) {
				if (rtpEndpoint == null) {
					return;
				}
				answer = rtpEndpoint.processOffer(sdp.replace("a=sendrecv", "a=recvonly"));
				negotiated = true;
				starting = false;
			}
			answerConsumer.accept(answer);
			synchronized (this) {
				applyState();
			}
		}

		private void applyState() {
			if (!negotiated || player == null) {
				return;
			}
			try {
				long position = positionMillis(status, System.currentTimeMillis());
				if (status.optBoolean(PARAM_PAUSED, true)) {
					player.pause();
					player.setPosition(position);
				} else {
					player.setPosition(position);
					player.play();
				}
			} catch (Exception e) {
				log.warn("Unable to synchronize whiteboard SIP audio in room {}", roomId, e);
			}
		}

		private synchronized void releaseMedia() {
			generation++;
			starting = false;
			negotiated = false;
			sipProcessor.ifPresent(processor -> {
				try {
					processor.destroy();
				} catch (Exception e) {
					log.debug("Unable to destroy whiteboard SIP processor in room {}", roomId, e);
				}
			});
			sipProcessor = Optional.empty();
			if (pipeline != null) {
				try {
					pipeline.release();
				} catch (Exception e) {
					log.debug("Unable to release whiteboard audio pipeline in room {}", roomId, e);
				}
			}
			pipeline = null;
			player = null;
			rtpEndpoint = null;
			room = null;
		}
	}
}
