package com.gen3.catalogue.model;

/** Couples a catalogue record with its dense vector, or null when it has not been indexed. */
public record AudiobookEmbedding(AudiobookRecord record, float[] vector) {}
