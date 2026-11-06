/*
 * Copyright 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.local.veil.ui

import androidx.graphics.shapes.TransformResult
import androidx.compose.ui.geometry.Offset
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** LoadingIndicator subset of AndroidX MaterialShapes, with the original control points.
 * Source: androidx/compose/material3/material3/MaterialShapes.kt.
 * Kept separate from decorative app shapes so their geometry stays unchanged.
 */
internal object LoadingShapes {
    private data class Vertex(val point: Offset, val rounding: CornerRounding = CornerRounding.Unrounded)
    private fun vertex(x: Float, y: Float, radius: Float = 0f) =
        Vertex(Offset(x, y), CornerRounding(radius))

    private fun polygon(points: List<Vertex>, reps: Int, mirror: Boolean = false): RoundedPolygon {
        val center = Offset(0.5f, 0.5f)
        val vertices = buildList {
            val count = if (mirror) reps * 2 else reps
            repeat(count) { section ->
                points.indices.forEach { index ->
                    val i = if (mirror && section % 2 != 0) points.lastIndex - index else index
                    if (!mirror || i > 0 || section % 2 == 0) {
                        val offset = points[i].point - center
                        val angle = atan2(offset.y, offset.x) * 180f / PI.toFloat()
                        val first = points.first().point - center
                        val firstAngle = atan2(first.y, first.x) * 180f / PI.toFloat()
                        val step = 360f / count
                        val degrees = step * section + if (mirror && section % 2 != 0)
                            step - angle + 2f * firstAngle else angle
                        val radians = degrees * PI.toFloat() / 180f
                        add(Vertex(center + Offset(cos(radians), sin(radians)) * offset.getDistance(), points[i].rounding))
                    }
                }
            }
        }
        return RoundedPolygon(
            vertices = FloatArray(vertices.size * 2) { i ->
                if (i % 2 == 0) vertices[i / 2].point.x else vertices[i / 2].point.y
            },
            perVertexRounding = vertices.map { it.rounding },
            centerX = 0.5f,
            centerY = 0.5f,
        ).normalized()
    }

    val sequence: List<RoundedPolygon> by lazy {
        listOf(
            polygon(listOf(vertex(.193f, .277f, .053f), vertex(.176f, .055f, .053f)), 10),
            RoundedPolygon.star(9, innerRadius = .8f, rounding = CornerRounding(.5f))
                .transformed { x, y -> TransformResult(y, -x) }.normalized(),
            polygon(listOf(vertex(.500f, -.009f, .172f), vertex(1.030f, .365f, .164f), vertex(.828f, .970f, .169f)), 1, true),
            polygon(listOf(vertex(.961f, .039f, .426f), vertex(1.001f, .428f), vertex(1.000f, .609f, 1f)), 2, true),
            RoundedPolygon.star(8, innerRadius = .8f, rounding = CornerRounding(.15f)).normalized(),
            polygon(listOf(vertex(1.237f, 1.236f, .258f), vertex(.500f, .918f, .233f)), 4),
            RoundedPolygon.circle().transformed { x, y ->
                val s = .70710677f
                TransformResult((x + y * .64f) * s, (y * .64f - x) * s)
            }.normalized(),
        )
    }
}
