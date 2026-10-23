package com.gen3.recommenderagent.storage.audiobook.model;

/** A catalogue record and its opaque database reference, shared by maintenance jobs. */
public record StoredAudiobook(AudiobookRecord record, String storageReference) {}
