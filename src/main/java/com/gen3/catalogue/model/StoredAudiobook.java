package com.gen3.catalogue.model;

/** A catalogue record and its opaque database reference, shared by maintenance jobs. */
public record StoredAudiobook(AudiobookRecord record, String storageReference) {}
