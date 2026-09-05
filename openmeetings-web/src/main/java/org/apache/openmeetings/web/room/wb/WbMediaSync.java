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
package org.apache.openmeetings.web.room.wb;

import static org.apache.openmeetings.db.dto.room.Whiteboard.ATTR_FILE_ID;
import static org.apache.openmeetings.db.dto.room.Whiteboard.ATTR_OMTYPE;
import static org.apache.openmeetings.db.dto.room.Whiteboard.ATTR_SLIDE;
import static org.apache.openmeetings.util.OpenmeetingsVariables.PARAM_STATUS;

import org.apache.openmeetings.db.dto.room.Whiteboard;
import org.apache.openmeetings.mediaserver.WbAudioProcessor;
import org.apache.openmeetings.web.app.WhiteboardManager;
import org.apache.wicket.injection.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

import jakarta.inject.Inject;

final class WbMediaSync {
	private static final Logger log = LoggerFactory.getLogger(WbMediaSync.class);

	@Inject
	private WhiteboardManager wbm;
	@Inject
	private WbAudioProcessor audioProcessor;

	private WbMediaSync() {
		Injector.get().inject(this);
	}

	static void sync(Long roomId, WbAction action, JSONObject obj) {
		try {
			new WbMediaSync().handle(roomId, action, obj);
		} catch (Exception e) {
			// Whiteboard synchronization must remain best-effort and must never block the WB action itself.
			log.warn("Unable to synchronize whiteboard media with SIP, room {}, action {}", roomId, action, e);
		}
	}

	private void handle(Long roomId, WbAction action, JSONObject obj) {
		switch (action) {
			case VIDEO_STATUS:
			{
				long wbId = obj.optLong("wbId", -1);
				String uid = obj.optString("uid");
				Whiteboard wb = wbm.get(roomId).get(wbId);
				JSONObject video = wb == null ? null : wb.get(uid);
				if (video != null && "Video".equals(video.optString(ATTR_OMTYPE))) {
					audioProcessor.updateVideo(
							roomId
							, wbId
							, video.optInt(ATTR_SLIDE, -1)
							, uid
							, video.optLong(ATTR_FILE_ID, -1)
							, video.optJSONObject(PARAM_STATUS));
				}
			}
				break;
			case DELETE_OBJ:
			{
				JSONArray arr = obj.optJSONArray("obj");
				if (arr != null) {
					for (int i = 0; i < arr.length(); ++i) {
						audioProcessor.removeVideo(roomId, arr.getJSONObject(i).optString("uid"));
					}
				}
			}
				break;
			case CLEAR_ALL, REMOVE_WB:
				audioProcessor.clearBoard(roomId, obj.optLong("wbId", -1));
				break;
			case CLEAR_SLIDE:
				audioProcessor.clearSlide(
						roomId
						, obj.optLong("wbId", -1)
						, obj.optInt(ATTR_SLIDE, -1));
				break;
			default:
				break;
		}
	}
}
