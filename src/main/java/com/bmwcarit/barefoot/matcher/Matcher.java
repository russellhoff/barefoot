/*
 * Copyright (C) 2015, BMW Car IT GmbH
 *
 * Author: Sebastian Mattheis <sebastian.mattheis@bmw-carit.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in
 * writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.bmwcarit.barefoot.matcher;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bmwcarit.barefoot.markov.Filter;
import com.bmwcarit.barefoot.roadmap.Distance;
import com.bmwcarit.barefoot.roadmap.Road;
import com.bmwcarit.barefoot.roadmap.RoadMap;
import com.bmwcarit.barefoot.roadmap.RoadPoint;
import com.bmwcarit.barefoot.roadmap.Route;
import com.bmwcarit.barefoot.scheduler.StaticScheduler;
import com.bmwcarit.barefoot.scheduler.Task;
import com.bmwcarit.barefoot.scheduler.StaticScheduler.InlineScheduler;
import com.bmwcarit.barefoot.spatial.SpatialOperator;
import com.bmwcarit.barefoot.topology.Cost;
import com.bmwcarit.barefoot.topology.Router;
import com.bmwcarit.barefoot.util.Stopwatch;
import com.bmwcarit.barefoot.util.Tuple;
import com.esri.core.geometry.GeometryEngine;
import com.esri.core.geometry.WktExportFlags;

/**
 * Matcher filter for Hidden Markov Model (HMM) map matching. It is a HMM filter (@{link Filter})
 * and determines emission and transition probabilities for map matching with HMM.
 */
public class Matcher extends Filter<MatcherCandidate, MatcherTransition, MatcherSample> {
    private final static Logger logger = LoggerFactory.getLogger(Matcher.class);

    private final RoadMap map;
    private final Router<Road, RoadPoint> router;
    private final Cost<Road> cost;
    private final SpatialOperator spatial;

    private double sig2 = Math.pow(5d, 2);
    private double sqrt_2pi_sig2 = Math.sqrt(2d * Math.PI * sig2);
    private double lambda = 1d / 10d;
    private double radius = 100;
    private double distance = 15000;

    public Matcher(RoadMap map, Router<Road, RoadPoint> router, Cost<Road> cost,
            SpatialOperator spatial) {
        this.map = map;
        this.router = router;
        this.cost = cost;
        this.spatial = spatial;
    }

    /**
     * Sets standard deviation in meters of gaussian distribution for defining emission
     * probabilities (default is 5 meters).
     *
     * @param sigma Standard deviation in meters of gaussian distribution for defining emission
     *        probabilities (default is 5 meters).
     */
    public void setSigma(double sigma) {
        this.sig2 = Math.pow(sigma, 2);
        this.sqrt_2pi_sig2 = Math.sqrt(2d * Math.PI * sig2);
    }

    /**
     * Sets lambda parameter of negative exponential distribution defining transition probabilities
     * (default is 1/5).
     *
     * @param lambda Lambda parameter of negative exponential distribution defining transition
     *        probabilities.
     */
    public void setLambda(double lambda) {
        this.lambda = lambda;
    }

    /**
     * Sets maximum radius for candidate selection in meters (default is 100 meters).
     *
     * @param radius Maximum radius for candidate selection in meters.
     */
    public void setMaxRadius(double radius) {
        this.radius = radius;
    }

    /**
     * Sets maximum transition distance in meters (default is 15000 meters).
     *
     * @param distance Maximum transition distance in meters.
     */
    public void setMaxDistance(double distance) {
        this.distance = distance;
    }

    @Override
    protected Set<Tuple<MatcherCandidate, Double>> candidates(MatcherSample sample) {
        if (logger.isTraceEnabled()) {
            logger.trace("finding candidates for sample {} {}", new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ssZ").format(sample.time()), GeometryEngine.geometryToWkt(
                    sample.point(), WktExportFlags.wktExportPoint));
        }

        Set<RoadPoint> points_ = map.spatial().radius(sample.point(), radius);
        Set<RoadPoint> points = Minset.minimize(points_);
        Set<Tuple<MatcherCandidate, Double>> candidates = new HashSet<Tuple<MatcherCandidate, Double>>();

        logger.trace("{} ({}) candidates", points.size(), points_.size());

        for (RoadPoint point : points) {
            double dz = spatial.distance(sample.point(), point.geometry());
            double emission = 1 / sqrt_2pi_sig2 * Math.exp((-1) * dz / (2 * sig2));

            MatcherCandidate candidate = new MatcherCandidate(point);
            candidates.add(new Tuple<MatcherCandidate, Double>(candidate, emission));

            logger.trace("{} {} {}", candidate.id(), dz, emission);
        }

        return candidates;
    }

    @Override
    protected Tuple<MatcherTransition, Double> transition(Tuple<MatcherSample, MatcherCandidate> predecessor,
            Tuple<MatcherSample, MatcherCandidate> candidate) {

        return null;
    }

    @Override
    protected Map<MatcherCandidate, Map<MatcherCandidate, Tuple<MatcherTransition, Double>>> transitions(
            final Tuple<MatcherSample, Set<MatcherCandidate>> predecessors,
            final Tuple<MatcherSample, Set<MatcherCandidate>> candidates) {

        if (logger.isTraceEnabled()) {
            logger.trace("finding transitions for sample {} {} with {} x {} candidates",
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(candidates.one().time()),
                    GeometryEngine.geometryToWkt(candidates.one().point(),
                            WktExportFlags.wktExportPoint), predecessors.two().size(), candidates
                            .two().size());
        }

        Stopwatch sw = new Stopwatch();
        sw.start();

        final Set<RoadPoint> targets = new HashSet<RoadPoint>();
        for (MatcherCandidate candidate : candidates.two()) {
            targets.add(candidate.point());
        }

        final AtomicInteger count = new AtomicInteger();
        final Map<MatcherCandidate, Map<MatcherCandidate, Tuple<MatcherTransition, Double>>> transitions =
                new ConcurrentHashMap<MatcherCandidate, Map<MatcherCandidate, Tuple<MatcherTransition, Double>>>();
        final double dt = spatial.distance(predecessors.one().point(), candidates.one().point());

        InlineScheduler scheduler = StaticScheduler.scheduler();
        for (final MatcherCandidate predecessor : predecessors.two()) {
            scheduler.spawn(new Task() {
                @Override
                public void run() {
                    Map<MatcherCandidate, Tuple<MatcherTransition, Double>> map =
                            new HashMap<MatcherCandidate, Tuple<MatcherTransition, Double>>();
                    Stopwatch sw = new Stopwatch();
                    sw.start();
                    Map<RoadPoint, List<Road>> routes =
                            router.route(predecessor.point(), targets, cost, new Distance(),
                                    distance);
                    sw.stop();

                    logger.trace("{} routes ({} ms)", routes.size(), sw.ms());

                    for (MatcherCandidate candidate : candidates.two()) {
                        List<Road> edges = routes.get(candidate.point());

                        if (edges == null) {
                            continue;
                        }

                        Route route = new Route(predecessor.point(), candidate.point(), edges);

                        // According to Newson and Krumm 2009, transition probability is lambda *
                        // Math.exp((-1.0) * lambda * Math.abs(dt - route.length())), however, we
                        // experimentally choose lambda * Math.exp((-1.0) * lambda * Math.max(0,
                        // route.length() - dt)) to avoid unnecessary routes in case of u-turns.
                        double transition =
                                lambda
                                        * Math.exp((-1.0) * lambda
                                                * Math.max(0, route.length() - dt));

                        map.put(candidate, new Tuple<MatcherTransition, Double>(new MatcherTransition(
                                route), transition));

                        logger.trace("{} -> {} {} {} {}", predecessor.id(), candidate.id(), dt,
                                route.length(), transition);
                        count.incrementAndGet();
                    }

                    transitions.put(predecessor, map);
                }
            });
        }
        if (!scheduler.sync()) {
            throw new RuntimeException();
        }

        sw.stop();

        logger.trace("{} transitions ({} ms)", count.get(), sw.ms());

        return transitions;
    }
}
