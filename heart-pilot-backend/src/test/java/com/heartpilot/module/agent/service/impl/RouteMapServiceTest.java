package com.heartpilot.module.agent.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RouteMapServiceTest {
    private final RouteMapService service = new RouteMapService("test-key", new ObjectMapper());

    @Test
    void keepsEndpointsWhileReducingLongWalkingPolyline() {
        String polyline =
                IntStream.range(0, 200)
                        .mapToObj(index -> (110 + index / 1000.0) + "," + (25 + index / 1000.0))
                        .reduce((left, right) -> left + ";" + right)
                        .orElseThrow();

        String simplified = service.simplifyPolyline(polyline, 40);
        String[] points = simplified.split(";");

        assertEquals(40, points.length);
        assertTrue(points[0].startsWith("110.0,25.0"));
        assertTrue(points[39].startsWith("110.199,25.199"));
    }
}
