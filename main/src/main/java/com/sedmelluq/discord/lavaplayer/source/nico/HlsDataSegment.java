package com.sedmelluq.discord.lavaplayer.source.nico;

import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegment;

import java.util.Map;

public class HlsDataSegment extends HlsStreamSegment {
    public final String directiveName;
    public final Map<String, String> directiveArguments;

    public HlsDataSegment(String name, Map<String, String> directiveArguments) {
        // null intentionally so that anything reading these fields can't process it
        // -- handling this segment incorrectly is an error.
        super(null, null, null);
        this.directiveName = name;
        this.directiveArguments = directiveArguments;
    }
}
