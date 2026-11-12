package com.zentro.notificationintegration.notification.preference;

import com.zentro.notificationintegration.delivery.sms.MockSmsSender;
import com.zentro.notificationintegration.notification.recipient.RecipientResolver.ResolvedRecipient;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Evaluates recipient and system channel preferences according to ZD-065.
 * Determines the eligible delivery channel for an event and recipient.
 */
@Component
public class ChannelPreferenceService {
  public static final String DEFAULT_CHANNEL = MockSmsSender.CHANNEL;
  private static final Set<String> SUPPORTED_CHANNELS = Set.of(DEFAULT_CHANNEL);

  public enum ChannelStatus {
    SUPPORTED,
    UNSUPPORTED,
    OPTED_OUT
  }

  public record ChannelDecision(String channel, ChannelStatus status) {
    public boolean isDeliverable() {
      return status == ChannelStatus.SUPPORTED && channel != null;
    }
  }

  /**
   * Resolves the channel to use given preferred channels (e.g. from preference rules).
   * Falls back to DEFAULT_CHANNEL if none specified or matches.
   */
  public ChannelDecision resolveChannel(ResolvedRecipient recipient, List<String> preferredChannels) {
    if (recipient == null) {
      return new ChannelDecision(null, ChannelStatus.UNSUPPORTED);
    }
    if (preferredChannels == null || preferredChannels.isEmpty()) {
      return new ChannelDecision(DEFAULT_CHANNEL, ChannelStatus.SUPPORTED);
    }
    for (String preferred : preferredChannels) {
      if (preferred != null) {
        String cleanChannel = preferred.strip().toUpperCase();
        if (SUPPORTED_CHANNELS.contains(cleanChannel)) {
          return new ChannelDecision(cleanChannel, ChannelStatus.SUPPORTED);
        }
      }
    }
    // Preferred channels were specified but none are supported by this service
    return new ChannelDecision(preferredChannels.get(0), ChannelStatus.UNSUPPORTED);
  }

  public boolean isChannelSupported(String channel) {
    return channel != null && SUPPORTED_CHANNELS.contains(channel.strip().toUpperCase());
  }
}
