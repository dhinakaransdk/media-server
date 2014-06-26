package com.kaltura.media.server.wowza.listeners;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.kaltura.client.KalturaApiException;
import com.kaltura.client.KalturaObjectBase;
import com.kaltura.client.enums.KalturaDVRStatus;
import com.kaltura.client.enums.KalturaMediaServerIndex;
import com.kaltura.client.types.KalturaLiveAsset;
import com.kaltura.client.types.KalturaLiveEntry;
import com.kaltura.client.types.KalturaLiveParams;
import com.kaltura.client.types.KalturaLiveStreamEntry;
import com.kaltura.infra.XmlUtils;
import com.kaltura.media.server.KalturaEventsManager;
import com.kaltura.media.server.KalturaServer;
import com.kaltura.media.server.events.IKalturaEvent;
import com.kaltura.media.server.events.KalturaEventType;
import com.kaltura.media.server.events.KalturaMetadataEvent;
import com.kaltura.media.server.events.KalturaStreamEvent;
import com.kaltura.media.server.managers.ILiveStreamManager;
import com.kaltura.media.server.wowza.LiveStreamManager;
import com.kaltura.media.server.wowza.events.KalturaApplicationInstanceEvent;
import com.kaltura.media.server.wowza.events.KalturaMediaEventType;
import com.kaltura.media.server.wowza.events.KalturaMediaStreamEvent;
import com.wowza.wms.amf.AMFData;
import com.wowza.wms.amf.AMFDataList;
import com.wowza.wms.amf.AMFDataObj;
import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.client.IClient;
import com.wowza.wms.dvr.DvrApplicationContext;
import com.wowza.wms.dvr.IDvrConstants;
import com.wowza.wms.medialist.MediaList;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.request.RequestFunction;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.MediaStreamActionNotifyBase;
import com.wowza.wms.stream.livedvr.ILiveStreamDvrRecorderControl;
import com.wowza.wms.stream.livepacketizer.ILiveStreamPacketizerControl;
import com.wowza.wms.stream.livetranscoder.ILiveStreamTranscoder;
import com.wowza.wms.stream.livetranscoder.ILiveStreamTranscoderControl;
import com.wowza.wms.stream.livetranscoder.ILiveStreamTranscoderNotify;
import com.wowza.wms.stream.publish.Publisher;
import com.wowza.wms.stream.publish.Stream;
import com.wowza.wms.transcoder.model.LiveStreamTranscoder;
import com.wowza.wms.transcoder.model.LiveStreamTranscoderActionNotifyBase;
import com.wowza.wms.transcoder.model.TranscoderStreamNameGroup;
import com.wowza.wms.util.MediaListUtils;

public class LiveStreamEntry extends ModuleBase {

	protected final static String REQUEST_PROPERTY_PARTNER_ID = "p";
	protected final static String REQUEST_PROPERTY_ENTRY_ID = "e";
	protected final static String REQUEST_PROPERTY_SERVER_INDEX = "i";
	protected final static String REQUEST_PROPERTY_TOKEN = "t";

	protected final static String CLIENT_PROPERTY_CONNECT_APP = "connectapp";
	protected final static String CLIENT_PROPERTY_PARTNER_ID = "partnerId";
	protected final static String CLIENT_PROPERTY_SERVER_INDEX = "serverIndex";
	protected final static String CLIENT_PROPERTY_ENTRY_ID = "entryId";
	protected final static String STREAM_PROPERTY_SUFFIX = "suffix";

	protected final static int INVALID_SERVER_INDEX = -1;

	protected static Logger logger = Logger.getLogger(LiveStreamEntry.class);

	private String applicationName;
	private LiveStreamManager liveStreamManager;
	private DvrRecorderControl dvrRecorderControl = new DvrRecorderControl();
	private LiveStreamListener liveStreamListener = new LiveStreamListener();
	private LiveStreamTranscoderListener liveStreamTranscoderListener = new LiveStreamTranscoderListener();
	private LiveStreamTranscoderActionListener liveStreamTranscoderActionListener = new LiveStreamTranscoderActionListener();
	private IApplicationInstance appInstance;
	
	private class DvrRecorderControl implements ILiveStreamDvrRecorderControl, ILiveStreamPacketizerControl {

		@Override
		public boolean shouldDvrRecord(String recorderName, IMediaStream stream) {
			return this.isThatStreamNeeded(stream);
		}

		private boolean isThatStreamNeeded(IMediaStream stream) {
			String streamName = stream.getName();
			IApplicationInstance appInstance = stream.getStreams().getAppInstance();
			DvrApplicationContext ctx = appInstance.getDvrApplicationContext();

			String entryId = null;
			IClient client = stream.getClient();
			if (client != null) {
				WMSProperties clientProperties = client.getProperties();
				if (!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID)) {
					logger.info("Stream [" + streamName + "] is not associated with entry");
					return false;
				}
				entryId = clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID);
			} else {
				Pattern pattern = Pattern.compile("(\\d_[\\d\\w]{8})_");
				Matcher matcher = pattern.matcher(streamName);
				if (!matcher.find()) {

					logger.info("Stream [" + streamName + "] does not match regex");
					return false;
				}

				entryId = matcher.group(1);
			}

			KalturaLiveStreamEntry liveStreamEntry = liveStreamManager.get(entryId);

			if (liveStreamEntry == null) {
				logger.debug("Stream [" + streamName + "] entry [" + entryId + "] not found");
				return false;
			}

			if (liveStreamEntry.dvrStatus != KalturaDVRStatus.ENABLED) {
				logger.debug("Stream [" + streamName + "] DVR disabled");
				return false;
			}

			int dvrWindow = liveStreamManager.getDvrWindow(liveStreamEntry);
			logger.debug("Stream [" + streamName + "] DVR window [" + dvrWindow + "]");

			ctx.setWindowDuration(dvrWindow);
			ctx.setArchiveStrategy(IDvrConstants.ARCHIVE_STRATEGY_DELETE);

			return true;
		}

		public boolean isLiveStreamPacketize(String packetizer, IMediaStream stream) {
			logger.debug("Packetizer [" + packetizer + ", " + stream.getName() + "]");

			if (packetizer.compareTo("dvrstreamingpacketizer") == 0) {
				logger.debug("Packetizer check shouldDvrRecord");
				return this.isThatStreamNeeded(stream);
			}

			return true;
		}
	}

	class TranscoderControl implements ILiveStreamTranscoderControl {
		public boolean isLiveStreamTranscode(String transcoder, IMediaStream stream)
		{
			if (stream.getName().endsWith("_publish") || stream.isTranscodeResult()){
				return false;
			}
				
			return true;
		}
	}
	
	class RestreamerStreamActionListener extends MediaStreamActionNotifyBase {
		
		Stream stream;

		@Override
		public void onPublish(IMediaStream mediaStream, String streamName, boolean flag, boolean flag1) {

			if(!mediaStream.isTranscodeResult()){
				logger.debug("Stream [" + streamName + "] already published");
				return;
			}
			
			Pattern pattern = Pattern.compile("^(\\d_[\\d\\w]{8})_\\d+");
			Matcher matcher = pattern.matcher(streamName);
			if (!matcher.find()) {
				logger.info("Stream [" + streamName + "] entry id regex didn't match");
				return;
			}

			String entryId = matcher.group(1);
			logger.info("Stream [" + streamName + "] entry id [" + entryId + "]");
				
			if(liveStreamManager == null || !liveStreamManager.shouldSync(entryId)){
				logger.info("Stream [" + streamName + "] entry id [" + entryId + "] should not be restreamed");
				return;
			}
			
			if(appInstance.getStreams().getStream(streamName + "_publish") != null){
				logger.debug("Stream [" + streamName + "] already published");
				return;
			}
				
			logger.info("Restreaming [" + streamName + "]");
			byte bytes[] = new byte[]{};
			Date date = new Date();
		
			stream = Stream.createInstance(appInstance, streamName + "_publish");
			Publisher publisher = stream.getPublisher();
			publisher.addVideoData(bytes, 0, date.getTime());
			stream.play(streamName, -2, -1, false);
		}

		@Override
		public void onUnPublish(IMediaStream imediastream, String s, boolean flag, boolean flag1) {
			if(stream != null){
				stream.close();
				stream = null;
			}
		}
	}
	
	class LiveStreamListener extends MediaStreamActionNotifyBase {

		public void onPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {

			IClient client = stream.getClient();
			int assetParamsId = Integer.MIN_VALUE;
			if (client != null){ // streamed from the client
				WMSProperties clientProperties = client.getProperties();
				logger.debug("Client IP: " + client.getIp().toString());
				if (!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID)){
					onClientConnect(client);
					if (!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID)){
						logger.error("Unauthenticated client tried to publish stream [" + streamName + "]");
						client.rejectConnection("Client did not authenticated", "Client did not authenticated");
						return;
					}
				}
	
				String entryId = clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID);
				KalturaMediaServerIndex serverIndex = KalturaMediaServerIndex.get(clientProperties.getPropertyInt(LiveStreamEntry.CLIENT_PROPERTY_SERVER_INDEX, LiveStreamEntry.INVALID_SERVER_INDEX));

				KalturaLiveEntry entry = liveStreamManager.get(entryId);
				if(entry == null){
					logger.debug("Unplanned disconnect occured earlier. Attempting reconnect.");
					entry = onClientConnect(client);
					if(entry == null){
						logger.error("Following reconnection attempt, client still not authenticated for stream [" + streamName + "]");
						client.rejectConnection("Client is not authenticated", "Client not authenticated");
						return;						
					}
				}
				
				synchronized (liveStreamManager.disconnectingTimers) {
					if (liveStreamManager.disconnectingTimers.containsKey(entryId))
					{
						logger.info("This entry is scheduled for disconnect. Cancelling the diconnect.");
						Timer disconnectTimer = liveStreamManager.disconnectingTimers.get(entryId);
						disconnectTimer.cancel();
						disconnectTimer.purge();
						liveStreamManager.disconnectingTimers.remove(entryId);
					}
				}
				
				logger.debug("Publish: " + entryId);

				if (!entryId.equals(streamName)){
					Pattern pattern = Pattern.compile("([01]_.{8})_(.+)$");
					Matcher matcher = pattern.matcher(streamName);
	
					if (!matcher.find()) {
						logger.error("Unknown published stream [" + streamName + "]");
						return;
					}
	
					if (!entryId.equals(matcher.group(1))) {
						logger.error("Published stream stream name [" + streamName + "] does not match entry id [" + entryId + "]");
						return;
					}
					
					String streamSuffix = matcher.group(2);
					KalturaLiveAsset liveAsset = liveStreamManager.getLiveAsset(entry.id, streamSuffix);
					if(liveAsset != null){
						assetParamsId = liveAsset.flavorParamsId;
					}
				}
				
				KalturaMediaStreamEvent event = new KalturaMediaStreamEvent(KalturaEventType.STREAM_PUBLISHED, entry, serverIndex, applicationName, stream, assetParamsId);
				KalturaEventsManager.raiseEvent(event);
			}
			else{ // streamed from the transcoder

				Pattern pattern = Pattern.compile("([01]_.{8})_(\\d+)$");
				Matcher matcher = pattern.matcher(streamName);

				if (!matcher.find()) {
					logger.error("Transcoder published stream [" + streamName + "] does not match entry regex");
					return;
				}

				String entryId = matcher.group(1);
				assetParamsId = Integer.parseInt(matcher.group(2));
				logger.debug("Stream [" + streamName + "] entry [" + entryId + "] asset params id [" + assetParamsId + "]");
				
				
				KalturaLiveParams liveAssetParams = liveStreamManager.getLiveAssetParams(assetParamsId);
				if(liveAssetParams != null && liveAssetParams.streamSuffix != null)
				{
					String sourceStreamName = entryId + "_" + liveAssetParams.streamSuffix;
					IMediaStream sourceStream = stream.getStreams().getStream(sourceStreamName);
					if(sourceStream == null){
						logger.error("Source stream [" + sourceStreamName + "] not found for stream [" + streamName + "]");
						return;
					}
					logger.debug("Source stream [" + sourceStreamName + "] found for stream [" + streamName + "]");
					
					client = sourceStream.getClient();
					if(client == null){
						logger.error("Client not found for stream [" + streamName + "]");
						return;
					}
					logger.debug("Client found for stream [" + streamName + "]");
					
					WMSProperties clientProperties = client.getProperties();
					KalturaMediaServerIndex serverIndex = KalturaMediaServerIndex.get(clientProperties.getPropertyInt(LiveStreamEntry.CLIENT_PROPERTY_SERVER_INDEX, LiveStreamEntry.INVALID_SERVER_INDEX));

					KalturaMediaStreamEvent event = new KalturaMediaStreamEvent(KalturaMediaEventType.MEDIA_STREAM_PUBLISHED, liveStreamManager.get(entryId), serverIndex, applicationName, stream, assetParamsId);
					KalturaEventsManager.raiseEvent(event);
				}
			}
		}

		public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {
			IClient client = stream.getClient();
			if (client == null)
				return;

			WMSProperties clientProperties = client.getProperties();
			if (!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID))
				return;

			KalturaLiveStreamEntry liveStreamEntry = liveStreamManager.get(clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID));
			KalturaMediaServerIndex serverIndex = KalturaMediaServerIndex.get(clientProperties.getPropertyInt(LiveStreamEntry.CLIENT_PROPERTY_SERVER_INDEX, LiveStreamEntry.INVALID_SERVER_INDEX));

			logger.debug("UnPublish: " + liveStreamEntry.id);

			KalturaMediaStreamEvent event = new KalturaMediaStreamEvent(KalturaEventType.STREAM_UNPUBLISHED, liveStreamEntry, serverIndex, applicationName, stream);
			KalturaEventsManager.raiseEvent(event);
		}

		@SuppressWarnings("unchecked")
		@Override
		public void onMetaData(IMediaStream stream, AMFPacket packet) {
			if(stream.getClientId() < 0){
				return;
			}

			IClient client = stream.getClient();
			if (client == null){
				return;
			}
			
			WMSProperties clientProperties = client.getProperties();
			if (!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID)){
				return;
			}

			String entryId = clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID);
			KalturaMediaServerIndex serverIndex = KalturaMediaServerIndex.get(clientProperties.getPropertyInt(LiveStreamEntry.CLIENT_PROPERTY_SERVER_INDEX, LiveStreamEntry.INVALID_SERVER_INDEX));

			KalturaLiveEntry entry = liveStreamManager.get(entryId);
			if(entry == null){
				return;						
			}
			
			byte[] buffer = packet.getData();
			if (buffer == null) {
				return;
			}

			if (packet.getSize() <= 2) {
				return;
			}

			int offset = 0;
			if (buffer[0] == 0)
				offset++;

			AMFDataList amfList = new AMFDataList(buffer, offset, buffer.length - offset);

			if (amfList.size() <= 1) {
				return;
			}

			if (amfList.get(0).getType() != AMFData.DATA_TYPE_STRING) {
				return;
			}

			if (amfList.get(1).getType() != AMFData.DATA_TYPE_OBJECT) {
				return;
			}

			String method = amfList.getString(0);
			AMFDataObj amfData = (AMFDataObj)  amfList.getObject(1);

			if(!amfData.containsKey("objectType")){
				return;
			}
		
			String objectType = amfData.getString("objectType");
			logger.info("Stream [" + stream.getName() + "] metadata [" + method + "] object type [" + objectType + "]");
			String objectClassName = "com.kaltura.client.types." + objectType;
			Class<KalturaObjectBase> clazz;
			KalturaObjectBase object;
			try {
				clazz = (Class<KalturaObjectBase>) Class.forName(objectClassName);
				object = clazz.newInstance();
			} catch (ClassNotFoundException e) {
				logger.info("Stream [" + stream.getName() + "] metadata [" + method + "] class [" + objectClassName + "] not found");
				return;
			} catch (InstantiationException | IllegalAccessException e) {
				logger.info("Stream [" + stream.getName() + "] metadata [" + method + "] class [" + objectClassName + "] failed to initialize: " + e.getMessage());
				return;
			}

			@SuppressWarnings("rawtypes")
			Iterator i = amfData.getKeys().iterator();
			Field field;
			while (i.hasNext()) {
				String attributeName = (String) i.next();
				field = null;
				try {
					field = clazz.getField(attributeName);
				} catch (NoSuchFieldException e) {
					logger.info("Stream [" + stream.getName() + "] metadata [" + method + "] class [" + objectClassName + "] field [" + attributeName + "] not found");
				} catch (SecurityException e) {
					logger.info("Stream [" + stream.getName() + "] metadata [" + method + "] class [" + objectClassName + "] field [" + attributeName + "]: " + e.getMessage());
				}
				if(field == null){
					continue;
				}

				try {
					field.set(object, amfData.get(attributeName));
				} catch (IllegalArgumentException | IllegalAccessException e) {
					logger.info("Stream [" + stream.getName() + "] metadata [" + method + "] class [" + objectClassName + "] set field [" + attributeName + "]: " + e.getMessage());
				}
			}
			
			IKalturaEvent event = new KalturaMetadataEvent(KalturaEventType.METADATA, entry, serverIndex, object);
			KalturaEventsManager.raiseEvent(event);
		}
	}
	
	class LiveStreamTranscoderSmilManager {

		protected final static String SMIL_VIDEOS_XPATH = "/smil/body/switch";

		private IApplicationInstance appInstance;
		
		public LiveStreamTranscoderSmilManager(IApplicationInstance appInstance) {
			this.appInstance = appInstance;
		}

		public synchronized void generate(String sourceGroupName, String destGroupName) {
			String appName = appInstance.getContextStr();
			logger.debug("Generate [" + appName + "/" + destGroupName + "] from source [" + sourceGroupName + "]");

			MediaList mediaList = MediaListUtils.parseMediaList(appInstance, sourceGroupName, "ngrp", null);
			if (mediaList == null) {
				logger.error("MediaList not found: " + appName + "/" + sourceGroupName);
				return;
			}

			String smil = mediaList.toSMILString();
			
			String filePath = appInstance.getStreamStoragePath() + File.separator + destGroupName + ".smil";
			File file = new File(filePath);
			if(file.exists()){
				File tmpFile;
				try {
					tmpFile = File.createTempFile(destGroupName, ".smil");
					PrintWriter out = new PrintWriter(tmpFile);
					out.print(smil);
					out.close();
				} catch (Exception e) {
					logger.error("Failed writing to temp file [" + filePath + "]: " + e.getMessage());
					return;
				}
				
				try {
					XmlUtils.merge(file, LiveStreamTranscoderSmilManager.SMIL_VIDEOS_XPATH, file, tmpFile);
				} catch (Exception e) {
					logger.error("Failed merging files [" + filePath + ", " + tmpFile.getAbsolutePath() + "]: " + e.getMessage());
					return;
				}
				
				tmpFile.delete();
			}
			else{
				try {
					PrintWriter out = new PrintWriter(file);
					out.print(smil);
					out.close();
				} catch (FileNotFoundException e) {
					logger.error("Failed writing to file [" + filePath + "]: " + e.getMessage());
					return;
				}
			}

			logger.info("Created smil file [" + filePath + "] for stream " + appName + "/" + destGroupName + ":\n" + smil + "\n\n");
		}

	}

	class LiveStreamTranscoderActionListener extends LiveStreamTranscoderActionNotifyBase {

		Map<String, LiveStreamTranscoderSmilManager> smilManagers = new HashMap<String, LiveStreamTranscoderSmilManager>();
		
		@Override
		public void onRegisterStreamNameGroup(LiveStreamTranscoder liveStreamTranscoder, TranscoderStreamNameGroup streamNameGroup){

			final IApplicationInstance appInstance = liveStreamTranscoder.getAppInstance();
			final String sourceGroupName = streamNameGroup.getStreamName();

			Pattern pattern = Pattern.compile("(\\d_[\\d\\w]{8})_([^_]+)_(.+)$");
			Matcher matcher = pattern.matcher(sourceGroupName);
			if (!matcher.find()) {
				logger.info("Group name [" + sourceGroupName + "] does not match group name regex");
				return;
			}

			final String entryId = matcher.group(1);
			String tag = matcher.group(3);
			
			final String destGroupName = entryId + "_" + tag;
			String appName = appInstance.getContextStr();
			
			logger.debug("Group [" + appName + "/" + destGroupName + "] for group name [" + streamNameGroup.getStreamName() + "]");
			
			IMediaStream stream = liveStreamTranscoder.getStream();
			if(stream == null){
				logger.error("Group [" + appName + "/" + destGroupName + "] source stream not found");
				return;
			}
			
			IClient client = stream.getClient();
			if(client == null){
				logger.error("Group [" + appName + "/" + destGroupName + "] client not found");
				return;
			}

			WMSProperties clientProperties = client.getProperties();
			if (!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID)) {
				logger.error("Group [" + appName + "/" + destGroupName + "] entry id not defined");
				return;
			}
			if(!entryId.equals(clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID))) {
				logger.error("Group [" + appName + "/" + destGroupName + "] entry id does not match group name");
				return;
			}
			
			TimerTask generateSmilTask = new TimerTask() {
				
				@Override
				public void run() {

					LiveStreamTranscoderSmilManager smilManager = null;
					
					synchronized (smilManagers) {
						
						if(smilManagers.containsKey(entryId)){
							smilManager = smilManagers.get(entryId);
						}
						else{
							smilManager = new LiveStreamTranscoderSmilManager(appInstance);
							smilManagers.put(entryId, smilManager);
						}
					}
					smilManager.generate(sourceGroupName, destGroupName);
				}
			};
			
			Timer timer = new Timer();
			timer.schedule(generateSmilTask, 1);
		}

		@Override
		public void onUnregisterStreamNameGroup(LiveStreamTranscoder liveStreamTranscoder, TranscoderStreamNameGroup streamNameGroup){

			IApplicationInstance appInstance = liveStreamTranscoder.getAppInstance();
			
			String appName = appInstance.getContextStr();

			String sourceGroupName = streamNameGroup.getStreamName();

			Pattern pattern = Pattern.compile("(\\d_[\\d\\w]{8})_([^_]+)_(.+)$");
			Matcher matcher = pattern.matcher(sourceGroupName);
			if (!matcher.find()) {
				logger.info("Group name [" + sourceGroupName + "] does not match group name regex");
				return;
			}

			final String entryId = matcher.group(1);
			String tag = matcher.group(3);
			
			final String destGroupName = entryId + "_" + tag;
			
			logger.debug("Group [" + appName + "/" + destGroupName + "]");

			smilManagers.remove(entryId);
			
			String filePath = appInstance.getStreamStoragePath() + File.separator + destGroupName + ".smil";
			
			File file = new File(filePath);
			if(file.exists())
				file.delete();
			
			logger.info("Group: Deleted smil file [" + filePath + "] for stream " + appName + "/" + destGroupName);
		}
	}
		
	class LiveStreamTranscoderListener implements ILiveStreamTranscoderNotify {

		@Override
		public void onLiveStreamTranscoderCreate(ILiveStreamTranscoder liveStreamTranscoder, IMediaStream mediaStream) {
			((LiveStreamTranscoder)liveStreamTranscoder).addActionListener(liveStreamTranscoderActionListener);
		}

		@Override
		public void onLiveStreamTranscoderDestroy(ILiveStreamTranscoder liveStreamTranscoder, IMediaStream mediaStream) {
		}

		@Override
		public void onLiveStreamTranscoderInit(ILiveStreamTranscoder iLiveStreamTranscoder, IMediaStream mediaStream) {
		}
	}

	public void onStreamCreate(IMediaStream stream) {
		logger.debug("LiveStreamEntry::onStreamCreate");
		stream.addClientListener(liveStreamListener);
		stream.addClientListener(new RestreamerStreamActionListener());
	}

	public void onDisconnect(IClient client) {
		WMSProperties clientProperties = client.getProperties();
		if (clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID)) {
			String entryId = clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID);
			logger.info("Entry removed [" + entryId + "]");

			KalturaStreamEvent event = new KalturaStreamEvent(KalturaEventType.STREAM_DISCONNECTED, liveStreamManager.get(entryId));
			KalturaEventsManager.raiseEvent(event);
		}
	}

	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		if(!setLiveStreamManager()){
			logger.error("Live Stream Manager is not loaded yet");
			client.rejectConnection("Live Stream Manager is not loaded yet", "Live Stream Manager is not loaded yet");
			return;
		}
			
		onClientConnect(client);
	}

	public KalturaLiveEntry onClientConnect(IClient client) {
		 WMSProperties clientProperties = client.getProperties();
         String entryPoint = clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_CONNECT_APP);
         logger.debug("Connect: " + entryPoint);

         String[] requestParts = entryPoint.split("\\?", 2);
         if(requestParts.length < 2)
        	 return null;
                 
         String[] queryParams = requestParts[1].split("&");
         HashMap<String, String> requestParams = new HashMap<String, String>();
         String[] queryParamsParts;
         for (int i = 0; i < queryParams.length; ++i) {
                 queryParamsParts = queryParams[i].split("=", 2);
                 if(queryParamsParts.length == 2)
                         requestParams.put(queryParamsParts[0], queryParamsParts[1]);
         }
         
         if(!requestParams.containsKey(LiveStreamEntry.REQUEST_PROPERTY_PARTNER_ID))
        	 return null;

         int partnerId = Integer.parseInt(requestParams.get(LiveStreamEntry.REQUEST_PROPERTY_PARTNER_ID));
         String entryId = requestParams.get(LiveStreamEntry.REQUEST_PROPERTY_ENTRY_ID);
         String token = requestParams.get(LiveStreamEntry.REQUEST_PROPERTY_TOKEN);

		try {
			KalturaLiveEntry entry = liveStreamManager.authenticate(entryId, partnerId, token);
			clientProperties.setProperty(LiveStreamEntry.CLIENT_PROPERTY_PARTNER_ID, partnerId);
			clientProperties.setProperty(LiveStreamEntry.CLIENT_PROPERTY_SERVER_INDEX, Integer.parseInt(requestParams.get(LiveStreamEntry.REQUEST_PROPERTY_SERVER_INDEX)));
			clientProperties.setProperty(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID, entryId);
			logger.info("Entry added [" + entryId + "]");

			return entry;
		} catch (KalturaApiException e) {
			logger.error("Entry authentication failed [" + entryId + "]: " + e.getMessage());
			client.rejectConnection("Unable to authenticate entry [" + entryId + "]", "Unable to authenticate entry [" + entryId + "]");
		}

         return null;
	}

	public boolean setLiveStreamManager() {
		if(liveStreamManager != null)
			return true;
		
		ILiveStreamManager serverLiveStreamManager = (ILiveStreamManager) KalturaServer.getManager(ILiveStreamManager.class);

		if (serverLiveStreamManager == null || !(serverLiveStreamManager instanceof LiveStreamManager)) {
			logger.error("Live stream manager not defined");
			return false;
		}

		liveStreamManager = (LiveStreamManager) serverLiveStreamManager;
		return true;
	}

	public void onAppStart(IApplicationInstance appInstance) {
		applicationName = appInstance.getApplication().getName();
		this.appInstance = appInstance;
		
		appInstance.setLiveStreamDvrRecorderControl(dvrRecorderControl);
		appInstance.setLiveStreamPacketizerControl(dvrRecorderControl);
		appInstance.setLiveStreamTranscoderControl(new TranscoderControl());
		
		setLiveStreamManager();
		
		appInstance.addLiveStreamTranscoderListener(liveStreamTranscoderListener);

		KalturaApplicationInstanceEvent event = new KalturaApplicationInstanceEvent(KalturaMediaEventType.APPLICATION_INSTANCE_STARTED, appInstance);
		KalturaEventsManager.raiseEvent(event);
	}
}