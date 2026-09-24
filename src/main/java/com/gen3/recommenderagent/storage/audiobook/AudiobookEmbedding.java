package com.gen3.recommenderagent.storage.audiobook;

/** Couples one catalogue record with the normalized dense vector persisted for that record. */
public record AudiobookEmbedding(AudiobookRecord record, float[] vector) {}
