package com.expnai.relay.features.partners.scout;

/** Loads the HTML of a public web page for creator discovery. */
public interface CreatorPageFetcher {

	String fetch(String url);
}