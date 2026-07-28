package io.metaloom.cortex.node.scenelayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.vertx.core.json.JsonObject;

/**
 * Derives depth bands and pairwise spatial relations from sampled objects.
 *
 * <p>
 * Pure arithmetic - no I/O, no node, no Loom. Everything interesting about this feature happens
 * here, which is why it lives apart from the node: it can be driven with synthetic depth gradients
 * and hand-placed boxes, and the expected predicates are then exact rather than plausible.
 * </p>
 *
 * <h2>Why relations are scored with z, not with a raw depth difference</h2>
 *
 * <p>
 * A nearness gap of 0.05 between two flat, confidently-measured objects is real. The same 0.05
 * between two objects whose own depth varies by 0.3 is noise. Dividing the gap by the pooled
 * interquartile spread says exactly that, and it is what stops the node asserting confident
 * nonsense about slanted surfaces, motion blur and the sky.
 * </p>
 */
public class RelationSolver {

	private final SceneLayoutNodeOptions options;

	public RelationSolver(SceneLayoutNodeOptions options) {
		this.options = options;
	}

	/**
	 * Assign a depth band to every object from the scene's own depth quantiles.
	 *
	 * @param objects the sampled objects
	 * @param map     the depth map, queried for its scene-wide quantiles
	 * @return the objects, each carrying a band
	 */
	public List<LayoutObject> assignBands(List<LayoutObject> objects, DepthMap map) {
		double fgCut = map.sceneQuantile(options.getForegroundQuantile());
		double bgCut = map.sceneQuantile(options.getBackgroundQuantile());

		List<LayoutObject> out = new ArrayList<>(objects.size());
		for (LayoutObject o : objects) {
			DepthBand band = DepthBand.MIDGROUND;
			if (o.depth() != null) {
				if (o.depth().near() >= fgCut) {
					band = DepthBand.FOREGROUND;
				} else if (o.depth().near() <= bgCut) {
					band = DepthBand.BACKGROUND;
				}
			}
			out.add(o.with(o.depth(), band));
		}
		return out;
	}

	/**
	 * Compute the relations between every ordered pair of objects.
	 *
	 * <p>
	 * Each pair contributes at most one depth predicate and one lateral predicate, plus optional
	 * occlusion and containment. Emitting every true statement would bury the interesting ones -
	 * the point of the output is that a caption model or a human can read it.
	 * </p>
	 *
	 * @param objects the banded objects
	 * @return the relations, ordered strongest-first and capped at {@code maxRelations}
	 */
	public List<SpatialRelation> solve(List<LayoutObject> objects) {
		List<SpatialRelation> relations = new ArrayList<>();

		for (int i = 0; i < objects.size(); i++) {
			for (int j = 0; j < objects.size(); j++) {
				if (i == j) {
					continue;
				}
				relate(objects.get(i), objects.get(j), relations);
			}
		}

		// Strongest first, so a maxRelations cut keeps the assertions worth keeping.
		relations.sort(Comparator.comparingDouble(SpatialRelation::confidence).reversed());
		if (relations.size() > options.getMaxRelations()) {
			return new ArrayList<>(relations.subList(0, options.getMaxRelations()));
		}
		return relations;
	}

	private void relate(LayoutObject a, LayoutObject b, List<SpatialRelation> out) {
		RelationPredicate depthPredicate = null;
		double z = 0;

		if (a.depth() != null && b.depth() != null) {
			double delta = a.depth().near() - b.depth().near();
			// Pool the two objects' own noise. The epsilon keeps a pair of perfectly flat objects
			// from producing an infinite z.
			double pooled = (a.depth().spread() + b.depth().spread()) / 2.0 + 1e-6;
			z = delta / pooled;

			double t = options.getDepthZThreshold();
			JsonObject evidence = new JsonObject()
				.put("deltaNear", round(delta))
				.put("z", round(z));

			if (z >= t) {
				depthPredicate = RelationPredicate.IN_FRONT_OF;
				out.add(new SpatialRelation(a.id(), depthPredicate, b.id(), Math.min(1, z / (2 * t)), evidence));
			} else if (z <= -t) {
				depthPredicate = RelationPredicate.BEHIND;
				out.add(new SpatialRelation(a.id(), depthPredicate, b.id(), Math.min(1, Math.abs(z) / (2 * t)), evidence));
			} else {
				depthPredicate = RelationPredicate.SAME_DEPTH;
				// Only once per unordered pair - "same depth" is symmetric and saying it twice is noise.
				if (a.id().compareTo(b.id()) < 0) {
					out.add(new SpatialRelation(a.id(), depthPredicate, b.id(), 1 - Math.abs(z) / t, evidence));
				}
			}
		}

		double intersection = a.box().intersectionArea(b.box());

		// Containment: b sits almost entirely inside a.
		if (b.box().area() > 0) {
			double containment = intersection / b.box().area();
			if (containment >= options.getContainmentRatio()) {
				out.add(new SpatialRelation(a.id(), RelationPredicate.CONTAINS, b.id(), containment,
					new JsonObject().put("containment", round(containment))));
			}
		}

		// Occlusion needs depth as well as overlap. Two overlapping boxes at the same depth are
		// adjacent, not occluding - calling that occlusion is the classic 2D-only mistake this
		// node exists to avoid.
		if (intersection > 0 && depthPredicate == RelationPredicate.IN_FRONT_OF) {
			double smaller = Math.min(a.box().area(), b.box().area());
			double overlap = smaller > 0 ? intersection / smaller : 0;
			if (overlap >= options.getOcclusionMinOverlap()) {
				JsonObject evidence = new JsonObject().put("overlap", round(overlap)).put("z", round(z));
				out.add(new SpatialRelation(a.id(), RelationPredicate.OCCLUDES, b.id(), overlap, evidence));
				out.add(new SpatialRelation(b.id(), RelationPredicate.OCCLUDED_BY, a.id(), overlap, evidence.copy()));
			}
		}

		// Lateral placement, only when the boxes are actually separated on that axis. Two boxes
		// that overlap horizontally have no meaningful left/right relationship.
		double meanSize = (Math.max(a.box().w(), a.box().h()) + Math.max(b.box().w(), b.box().h())) / 2.0;
		double gapRatio = meanSize > 0 ? a.box().gap(b.box()) / meanSize : Double.MAX_VALUE;

		if (!a.box().overlapsX(b.box())) {
			RelationPredicate lateral = a.box().centerX() < b.box().centerX() ? RelationPredicate.LEFT_OF : RelationPredicate.RIGHT_OF;
			out.add(new SpatialRelation(a.id(), lateral, b.id(), confidenceFromGap(gapRatio),
				new JsonObject().put("gapRatio", round(gapRatio))));
		} else if (!a.box().overlapsY(b.box())) {
			RelationPredicate vertical = a.box().centerY() < b.box().centerY() ? RelationPredicate.ABOVE : RelationPredicate.BELOW;
			out.add(new SpatialRelation(a.id(), vertical, b.id(), confidenceFromGap(gapRatio),
				new JsonObject().put("gapRatio", round(gapRatio))));
		}

		// Proximity: close in the image plane and at indistinguishable depth. Emitted once per
		// unordered pair for the same reason as SAME_DEPTH.
		if (depthPredicate == RelationPredicate.SAME_DEPTH
			&& gapRatio <= options.getNextToMaxGap()
			&& a.id().compareTo(b.id()) < 0) {
			out.add(new SpatialRelation(a.id(), RelationPredicate.NEXT_TO, b.id(), 1 - gapRatio / options.getNextToMaxGap(),
				new JsonObject().put("gapRatio", round(gapRatio))));
		}
	}

	/**
	 * Build the readable phrase list.
	 *
	 * <p>
	 * These exist because the primary consumers are LLM prompts and text search. Giving each of
	 * them its own predicate-to-English mapping would mean writing the same bug twice.
	 * </p>
	 *
	 * @param objects   the banded objects
	 * @param relations the asserted relations
	 * @return one sentence per band and per relation
	 */
	public List<String> phrases(List<LayoutObject> objects, List<SpatialRelation> relations) {
		List<String> phrases = new ArrayList<>();
		for (LayoutObject o : objects) {
			if (o.band() != null) {
				phrases.add(o.id() + " " + o.band().phrase());
			}
		}
		for (SpatialRelation r : relations) {
			// SAME_DEPTH is implied by the band sentences and reads as filler next to them.
			if (r.predicate() != RelationPredicate.SAME_DEPTH) {
				phrases.add(r.subjectId() + " " + r.predicate().phrase() + " " + r.objectId());
			}
		}
		return phrases;
	}

	/** A wider gap is a more confident left/right or above/below claim, saturating at one box-size. */
	private static double confidenceFromGap(double gapRatio) {
		return Math.max(0.1, Math.min(1, gapRatio));
	}

	private static double round(double value) {
		return Math.round(value * 1_000_000d) / 1_000_000d;
	}
}
