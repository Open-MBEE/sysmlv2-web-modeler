package org.omg.sysml.plantuml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.FeatureMembership;
import org.omg.sysml.lang.sysml.FeatureValue;
import org.omg.sysml.lang.sysml.Membership;
import org.omg.sysml.lang.sysml.Namespace;
import org.omg.sysml.lang.sysml.Relationship;
import org.omg.sysml.lang.sysml.Specialization;
import org.omg.sysml.lang.sysml.Type;
import org.omg.sysml.util.FeatureUtil;

class InheritKey {
    public final Type[] keys;
    private final boolean isDirect;

    private static List<Feature> safeInheritedFeatures(Type type) {
        try {
            return type.getInheritedFeature();
        } catch (NullPointerException ex) {
            // Some Pilot-derived features fail during instantiation resolution.
            // Degrade to "no inherited feature" so visualization can continue.
            return Collections.emptyList();
        }
    }

    private static List<Membership> safeInheritedMemberships(Type type) {
        try {
            return type.getInheritedMembership();
        } catch (NullPointerException ex) {
            // Some Pilot-derived memberships fail during instantiation resolution.
            // Degrade to "no inherited membership" so visualization can continue.
            return Collections.emptyList();
        }
    }

    private static boolean containsWithRedefined(List<Feature> features, Feature feature) {
        for (Feature member : features) {
            if (member.equals(feature) || matchRedefined(member, feature)) {
                return true;
            }
        }
        return false;
    }

    private static List<Feature> belongingFeatures(Type type) {
        List<Feature> features = new ArrayList<>();
        if (type == null) {
            return features;
        }

        for (Relationship relationship : Visitor.toOwnedRelationshipArray(type)) {
            if (!(relationship instanceof FeatureMembership) && !(relationship instanceof FeatureValue)) {
                continue;
            }
            for (Element target : relationship.getTarget()) {
                features.add((Feature) target);
            }
        }
        return features;
    }

    private static boolean isBelonging(Type type, Feature feature) {
        if (type == null) {
            return false;
        }
        if (containsWithRedefined(belongingFeatures(type), feature)) {
            return true;
        }
        if (containsWithRedefined(safeInheritedFeatures(type), feature)) {
            return true;
        }
        return false;
    }

    private static boolean isBelonging(Type type, Membership membership) {
        if (type == null) {
            return false;
        }
        if (type.getOwnedMembership().contains(membership)) {
            return true;
        }
        if (safeInheritedMemberships(type).contains(membership)) {
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (Type key : keys) {
            hash ^= key.hashCode();
        }
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof InheritKey)) {
            return false;
        }
        InheritKey inheritKey = (InheritKey) other;
        int length = keys.length;
        if (length != inheritKey.keys.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (!keys[i].equals(inheritKey.keys[i])) {
                return false;
            }
        }
        return true;
    }

    private static int findKeyType(List<Namespace> namespaces, Feature feature) {
        for (int i = namespaces.size() - 1; i >= 0; i--) {
            Namespace namespace = namespaces.get(i);
            if (!(namespace instanceof Type)) {
                continue;
            }
            Type type = (Type) namespace;
            if (isBelonging(type, feature)) {
                return i;
            }
        }
        return -1;
    }

    private static int findKeyType(List<Namespace> namespaces, Membership membership) {
        for (int i = namespaces.size() - 1; i >= 0; i--) {
            Namespace namespace = namespaces.get(i);
            if (!(namespace instanceof Type)) {
                continue;
            }
            Type type = (Type) namespace;
            if (isBelonging(type, membership)) {
                return i;
            }
        }
        return -1;
    }

    private static void fill(Type[] dest, List<Namespace> namespaces, List<Integer> path, int size) {
        for (int i = 0; i < size; i++) {
            int index = path.get(i).intValue();
            Type type = (Type) namespaces.get(index);
            dest[i] = type;
        }
    }

    private InheritKey(List<Namespace> namespaces, List<Integer> path, int size) {
        Type[] values = new Type[size];
        fill(values, namespaces, path, size);
        this.keys = values;
        this.isDirect = true;
    }

    private InheritKey(List<Namespace> namespaces, List<Integer> path, int size, Type type) {
        Type[] values = new Type[size + 1];
        fill(values, namespaces, path, size);
        values[size] = type;
        this.keys = values;
        this.isDirect = false;
    }

    private InheritKey(Type type) {
        Type[] values = new Type[1];
        values[0] = type;
        this.keys = values;
        this.isDirect = false;
    }

    private InheritKey(InheritKey inheritKey, boolean isDirect) {
        this.keys = inheritKey.keys;
        this.isDirect = isDirect;
    }

    private InheritKey(InheritKey inheritKey, int size) {
        this.keys = new Type[size];
        this.isDirect = inheritKey.isDirect;
        System.arraycopy(inheritKey.keys, 0, this.keys, 0, size);
    }

    public static InheritKey makeTargetKey(Type type, Feature feature) {
        if (isBelonging(type, feature)) {
            return new InheritKey(type);
        }
        return null;
    }

    public static InheritKey makeIndirect(InheritKey inheritKey) {
        if (inheritKey == null) {
            return null;
        }
        return new InheritKey(inheritKey, false);
    }

    public static InheritKey findTop(InheritKey inheritKey, Feature feature) {
        if (inheritKey == null) {
            return null;
        }
        int top = inheritKey.keys.length - 1;
        for (int i = top; i >= 0; i--) {
            if (!isBelonging(inheritKey.keys[i], feature)) {
                continue;
            }
            if (i == top) {
                return inheritKey;
            }
            return new InheritKey(inheritKey, i + 1);
        }
        return null;
    }

    private static Type identifyRedefiningTargetOwner(Type type, Feature feature, boolean directOnly) {
        if (type == null) {
            return null;
        }
        for (Specialization specialization : type.getOwnedSpecialization()) {
            Type general = specialization.getGeneral();
            if (general == null) {
                continue;
            }
            if (directOnly) {
                if (isBelonging(general, feature)) {
                    return general;
                }
                continue;
            }
            if (containsWithRedefined(belongingFeatures(general), feature)) {
                return general;
            }
        }
        return null;
    }

    private InheritKey(InheritKey inheritKey, int index, Type type) {
        if (index > 0) {
            this.keys = new Type[index + 1];
            System.arraycopy(inheritKey.keys, 0, this.keys, 0, index);
            this.keys[index] = type;
        } else {
            Type[] values = new Type[1];
            values[0] = type;
            this.keys = values;
        }
        this.isDirect = false;
    }

    public static InheritKey makeInheritKeyForRedefiningTarget(InheritKey inheritKey, Feature feature, boolean directOnly) {
        if (inheritKey == null) {
            return null;
        }
        Type[] values = inheritKey.keys;
        for (int i = values.length - 1; i >= 0; i--) {
            Type owner = values[i];
            Type redefiningTargetOwner = identifyRedefiningTargetOwner(owner, feature, directOnly);
            if (redefiningTargetOwner != null) {
                return new InheritKey(inheritKey, i, redefiningTargetOwner);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (keys.length == 0) {
            return "Empty InherityKey";
        }
        builder.append("InheritKey: [");
        builder.append(keys[0].getDeclaredName());
        for (int i = 1; i < keys.length; i++) {
            builder.append(",");
            builder.append(keys[i].getDeclaredName());
        }
        builder.append(isDirect ? ']' : ')');
        return builder.toString();
    }

    private static InheritKey constructInternal(List<Namespace> namespaces, List<Integer> path, int keyType) {
        if (keyType < 0) {
            return null;
        }
        for (int size = path.size(); size > 0; size--) {
            int pathEntry = path.get(size - 1).intValue();
            if (pathEntry == keyType) {
                return new InheritKey(namespaces, path, size);
            }
            if (pathEntry < keyType) {
                Type type = (Type) namespaces.get(keyType);
                return new InheritKey(namespaces, path, size, type);
            }
        }
        Type type = (Type) namespaces.get(keyType);
        return new InheritKey(type);
    }

    public static InheritKey construct(List<Namespace> namespaces, List<Integer> path, Feature feature) {
        int keyType = findKeyType(namespaces, feature);
        return constructInternal(namespaces, path, keyType);
    }

    public static InheritKey construct(List<Namespace> namespaces, List<Integer> path, Membership membership) {
        int keyType = findKeyType(namespaces, membership);
        return constructInternal(namespaces, path, keyType);
    }

    private static boolean matchRedefined(Feature feature1, Feature feature2) {
        Set<Feature> redefined = FeatureUtil.getAllRedefinedFeaturesOf(feature1);
        return redefined.contains(feature2);
    }

    public static boolean matchElementWithRedefined(Element element1, Element element2) {
        if (element1.equals(element2)) {
            return true;
        }
        if (element1 instanceof Feature && element2 instanceof Feature) {
            return matchRedefined((Feature) element1, (Feature) element2);
        }
        return false;
    }

    public static boolean match(InheritKey inheritKey, List<Namespace> namespaces, List<Integer> path) {
        if (inheritKey == null) {
            return path.isEmpty();
        }
        int namespaceCount = namespaces.size();
        if (namespaceCount == 0) {
            return false;
        }
        int pathCount = path.size();
        int keyCount = inheritKey.keys.length;
        int diff = keyCount - pathCount;
        if (diff != 0 && diff != 1) {
            return false;
        }
        for (int i = 0; i < pathCount; i++) {
            int namespaceIndex = path.get(i).intValue();
            Namespace namespace = namespaces.get(namespaceIndex);
            if (!matchElementWithRedefined(namespace, inheritKey.keys[i])) {
                return false;
            }
        }
        if (diff == 0) {
            return true;
        }
        if (inheritKey.isDirect) {
            return false;
        }
        return matchElementWithRedefined(
            (Element) namespaces.get(namespaceCount - 1),
            inheritKey.keys[keyCount - 1]
        );
    }

    public static boolean isDirectInherit(InheritKey inheritKey) {
        if (inheritKey == null) {
            return false;
        }
        return inheritKey.isDirect;
    }
}
