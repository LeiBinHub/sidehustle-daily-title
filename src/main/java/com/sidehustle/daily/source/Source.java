package com.sidehustle.daily.source;

import com.sidehustle.daily.model.Post;

import java.util.List;

/** A data source. Implementations must be read-only and respect the platform's rate limits and terms. */
public interface Source {

    String name();

    /** Whether the source is enabled in config and has the credentials it needs. */
    boolean isReady();

    /** @param sinceEpoch only return posts created at or after this epoch second. */
    List<Post> fetch(long sinceEpoch) throws Exception;
}
