/*
 * Copyright @ 2019-Present 8x8, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.shim;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.format.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.util.*;
import org.jivesoftware.smack.packet.*;

import java.io.*;
import java.util.*;

/**
 * Handles Colibri-related logic for a {@link Conference}, e.g.
 * creates/expires contents, describes the conference in XML.
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ConferenceShim
{
    /**
     * The {@link Logger} used by the {@link ConferenceShim} class to print
     * debug information.
     */
    private static final Logger logger = Logger.getLogger(ConferenceShim.class);

    /**
     * The corresponding {@link Conference}.
     */
    public final Conference conference;

    /**
     * The list of contents in this conference.
     */
    private final Map<MediaType, ContentShim> contents = new HashMap<>();

    /**
     * Initializes a new {@link ConferenceShim} instance.
     *
     * @param conference the corresponding conference.
     */
    public ConferenceShim(Conference conference)
    {
        this.conference = conference;
    }

    /**
     * Gets the content of type {@code type}, creating it if necessary.
     *
     * @param type the media type of the content to add.
     *
     * @return the content.
     */
    public ContentShim getOrCreateContent(MediaType type)
    {
        synchronized (contents)
        {
            return contents.computeIfAbsent(type,
                    key -> new ContentShim(getConference(), type));
        }
    }

    /**
     * @return the corresponding conference.
     */
    public Conference getConference()
    {
        return conference;
    }

    /**
     * Gets a copy of the list of contents.
     *
     * @return
     */
    public Collection<ContentShim> getContents()
    {
        synchronized (contents)
        {
            return new ArrayList<>(contents.values());
        }
    }

    void describeChannelBundles(
            ColibriConferenceIQ iq,
            Set<String> endpointIds)
            throws VideobridgeShim.IqProcessingException
    {
        for (AbstractEndpoint endpoint : conference.getEndpoints())
        {
            String endpointId = endpoint.getID();
            if (endpointIds.contains(endpointId))
            {
                ColibriConferenceIQ.ChannelBundle responseBundleIQ
                    = new ColibriConferenceIQ.ChannelBundle(endpointId);
                try
                {
                    endpoint.describe(responseBundleIQ);
                }
                catch (IOException ioe)
                {
                    throw new VideobridgeShim.IqProcessingException(
                            XMPPError.Condition.internal_server_error,
                            ioe.getMessage());
                }

                iq.addChannelBundle(responseBundleIQ);
            }
        }
    }

    /**
     * Adds the endpoint of this <tt>Conference</tt> as
     * <tt>ColibriConferenceIQ.Endpoint</tt> instances in <tt>iq</tt>.
     * @param iq the <tt>ColibriConferenceIQ</tt> in which to describe.
     */
    void describeEndpoints(ColibriConferenceIQ iq)
    {
        conference.getEndpoints().forEach(
                en -> iq.addEndpoint(
                        new ColibriConferenceIQ.Endpoint(
                                en.getID(), en.getStatsId(), en.getDisplayName())));
    }

    public void describeShallow(ColibriConferenceIQ iq)
    {
        iq.setID(conference.getID());
        iq.setName(conference.getName());
    }

    public String getId()
    {
        return conference.getID();
    }

    /**
     * Sets the values of the properties of a specific
     * <tt>ColibriConferenceIQ</tt> to the values of the respective
     * properties of this instance. Thus, the specified <tt>iq</tt> may be
     * thought of as a description of this instance.
     * <p>
     * <b>Note</b>: The copying of the values is deep i.e. the
     * <tt>Contents</tt>s of this instance are described in the specified
     * <tt>iq</tt>.
     * </p>
     *
     * @param iq the <tt>ColibriConferenceIQ</tt> to set the values of the
     * properties of this instance on
     */
    public void describeDeep(ColibriConferenceIQ iq)
    {
        describeShallow(iq);

        for (ContentShim contentShim : getContents())
        {
            ColibriConferenceIQ.Content contentIQ
                = iq.getOrCreateContent(contentShim.getMediaType().toString());

            for (ChannelShim channelShim : contentShim.getChannelShims())
            {
                ColibriConferenceIQ.Channel channelIQ
                    = new ColibriConferenceIQ.Channel();

                channelShim.describe(channelIQ);
                contentIQ.addChannel(channelIQ);
            }
        }
        // Do we also want endpoint-s anc channel-bundle-id-s?
    }

    /**
     * Processes an Octo channel for this conference.
     * @param channel the channel that was received.
     * @param mediaType the channel's media type.
     * @return
     */
    public void processOctoChannels(
                @NotNull ColibriConferenceIQ.OctoChannel audioChannel,
                @NotNull ColibriConferenceIQ.OctoChannel videoChannel)
    {
        OctoTentacle tentacle = conference.getTentacle();

        int expire
                = Math.min(audioChannel.getExpire(), videoChannel.getExpire());
        if (expire == 0)
        {
            tentacle.expire();
        }
        else
        {
            Set<String> relays = new HashSet<>(audioChannel.getRelays());
            relays.addAll(videoChannel.getRelays());
            tentacle.setRelays(relays);
        }

        Set<RTPHdrExtPacketExtension> headerExtensions
                = new HashSet<>(audioChannel.getRtpHeaderExtensions());
        headerExtensions.addAll(videoChannel.getRtpHeaderExtensions());

        // Like for payload types, we never clear the transceiver's list of RTP
        // header extensions. See the note in #addPayloadTypes.
        headerExtensions.forEach(ext ->
              tentacle.addRtpExtension(
                      Byte.valueOf(ext.getID()),
                      new RTPExtension(ext.getURI())));

        Map<PayloadTypePacketExtension, MediaType> payloadTypes
                = new HashMap<>();
        audioChannel.getPayloadTypes()
                .forEach(ext -> payloadTypes.put(ext, MediaType.AUDIO));
        videoChannel.getPayloadTypes()
                .forEach(ext -> payloadTypes.put(ext, MediaType.VIDEO));

        payloadTypes.forEach((ext, mediaType) -> {
            PayloadType pt = PayloadTypeUtil.create(ext, mediaType);
            if (pt == null)
            {
                logger.warn("Unrecognized payload type " + ext.toXML());
            }
            else
            {
                tentacle.addPayloadType(pt);
            }
        });

        tentacle.setSources(
                audioChannel.getSources(),
                videoChannel.getSources(),
                videoChannel.getSourceGroups());
    }

    /**
     * Updates an <tt>Endpoint</tt> of this <tt>Conference</tt> with the
     * information contained in <tt>colibriEndpoint</tt>. The ID of
     * <tt>colibriEndpoint</tt> is used to select the <tt>Endpoint</tt> to
     * update.
     *
     * @param colibriEndpoint a <tt>ColibriConferenceIQ.Endpoint</tt> instance
     * that contains information to be set on an <tt>Endpoint</tt> instance of
     * this <tt>Conference</tt>.
     */
    void updateEndpoint(ColibriConferenceIQ.Endpoint colibriEndpoint)
    {
        String id = colibriEndpoint.getId();

        if (id != null)
        {
            AbstractEndpoint endpoint = conference.getEndpoint(id);

            if (endpoint != null)
            {
                endpoint.setDisplayName(colibriEndpoint.getDisplayName());
                endpoint.setStatsId(colibriEndpoint.getStatsId());
            }
        }
    }
}