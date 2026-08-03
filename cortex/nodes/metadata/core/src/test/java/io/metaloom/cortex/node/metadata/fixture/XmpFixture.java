package io.metaloom.cortex.node.metadata.fixture;

/**
 * Minimal, valid XMP packets for tests - embedded packets and {@code .xmp} sidecars use the same
 * RDF/XML, which is the point.
 */
public final class XmpFixture {

	private XmpFixture() {
	}

	/**
	 * A packet carrying a title, description, one creator and two keywords, plus a Creative Commons
	 * licence. Enough to exercise "XMP beats EXIF for authored text" against a real parser.
	 */
	public static String titled(String title, String description, String creator) {
		return packet("""
			<dc:title><rdf:Alt><rdf:li xml:lang="x-default">%s</rdf:li></rdf:Alt></dc:title>
			<dc:description><rdf:Alt><rdf:li xml:lang="x-default">%s</rdf:li></rdf:Alt></dc:description>
			<dc:creator><rdf:Seq><rdf:li>%s</rdf:li></rdf:Seq></dc:creator>
			<dc:subject><rdf:Bag><rdf:li>sunrise</rdf:li><rdf:li>mountain</rdf:li></rdf:Bag></dc:subject>
			<cc:license rdf:resource="https://creativecommons.org/licenses/by/4.0/"/>
			""".formatted(title, description, creator));
	}

	/** A sidecar carrying only a title, so a test can prove the sidecar was read at all. */
	public static String sidecarTitle(String title) {
		return packet("""
			<dc:title><rdf:Alt><rdf:li xml:lang="x-default">%s</rdf:li></rdf:Alt></dc:title>
			""".formatted(title));
	}

	private static String packet(String properties) {
		return """
			<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
			<x:xmpmeta xmlns:x="adobe:ns:meta/">
			 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
			  <rdf:Description rdf:about=""
			    xmlns:dc="http://purl.org/dc/elements/1.1/"
			    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
			    xmlns:xmpRights="http://ns.adobe.com/xap/1.0/rights/"
			    xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
			    xmlns:cc="http://creativecommons.org/ns#">
			%s
			  </rdf:Description>
			 </rdf:RDF>
			</x:xmpmeta>
			<?xpacket end="w"?>
			""".formatted(properties);
	}
}
