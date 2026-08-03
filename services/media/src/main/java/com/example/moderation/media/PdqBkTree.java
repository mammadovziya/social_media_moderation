package com.example.moderation.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.Collectors;

final class PdqBkTree {
    private static final long SHUFFLE_SEED = 0x504451424b545245L;
    private final Node root;

    private PdqBkTree(Node root) {
        this.root = root;
    }

    static PdqBkTree empty() {
        return new PdqBkTree(null);
    }

    static PdqBkTree fromHexStrings(List<String> hashes) {
        List<PdqHashValue> values = hashes.stream()
                .map(PdqHashValue::parse)
                .distinct()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(values, new Random(SHUFFLE_SEED));
        return new PdqBkTree(build(values));
    }

    OptionalInt nearestDistance(String hash) {
        return nearestDistance(PdqHashValue.parse(hash));
    }

    OptionalInt nearestDistance(PdqHashValue target) {
        if (root == null) {
            return OptionalInt.empty();
        }

        int bestDistance = 257;
        PriorityQueue<SearchCandidate> candidates = new PriorityQueue<>(
                Comparator.comparingInt(SearchCandidate::lowerBound));
        candidates.add(new SearchCandidate(root, 0));

        while (!candidates.isEmpty()) {
            SearchCandidate candidate = candidates.remove();
            if (candidate.lowerBound() >= bestDistance) {
                break;
            }

            Node node = candidate.node();
            int distance = target.hammingDistance(node.value);
            if (distance < bestDistance) {
                bestDistance = distance;
                if (bestDistance == 0) {
                    break;
                }
            }

            for (Map.Entry<Integer, Node> child : node.children.entrySet()) {
                int lowerBound = Math.abs(distance - child.getKey());
                if (lowerBound < bestDistance) {
                    candidates.add(new SearchCandidate(child.getValue(), lowerBound));
                }
            }
        }
        return OptionalInt.of(bestDistance);
    }

    private static Node build(List<PdqHashValue> values) {
        if (values.isEmpty()) {
            return null;
        }
        Node root = new Node(values.getFirst());
        for (int index = 1; index < values.size(); index++) {
            insert(root, values.get(index));
        }
        return root;
    }

    private static void insert(Node root, PdqHashValue value) {
        Node current = root;
        while (true) {
            int distance = value.hammingDistance(current.value);
            if (distance == 0) {
                return;
            }
            Node child = current.children.get(distance);
            if (child == null) {
                current.children.put(distance, new Node(value));
                return;
            }
            current = child;
        }
    }

    private static final class Node {
        private final PdqHashValue value;
        private final Map<Integer, Node> children = new HashMap<>();

        private Node(PdqHashValue value) {
            this.value = value;
        }
    }

    private record SearchCandidate(Node node, int lowerBound) {}
}
