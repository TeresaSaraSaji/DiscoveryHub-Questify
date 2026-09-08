package com.discoveryhub.tools.corpus;

import com.discoveryhub.contracts.Custodian;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fictional staff of Meridian Dynamics. The order of this list is part of the contract: the
 * first five entries are the principals of the planted narrative and are referenced by
 * {@link Narrative} through their short keys, so ids stay stable when the roster grows.
 */
final class Roster {

    static final String DOMAIN = "meridian-dynamics.com";

    /** {shortKey, displayName, department, title} */
    private static final String[][] PEOPLE = {
            // narrative participants — keep first and keep the order
            {"dana", "Dana Whitfield", "SALES", "VP, Public Sector Sales"},
            {"marcus", "Marcus Ellery", "SALES", "Director, Bids & Proposals"},
            {"priya", "Priya Raghunathan", "FINANCE", "Finance Manager, Contracts"},
            {"owen", "Owen Castellanos", "LEGAL", "General Counsel"},
            {"renee", "Renee Toussaint", "OPERATIONS", "Program Manager, Transit"},
            {"lena", "Lena Ostrowski", "LEGAL", "Senior Counsel, Compliance"},
            {"holly", "Holly Ravensworth", "OPERATIONS", "Records Manager"},
            {"declan", "Declan Moriarty", "SALES", "Sales Engineer"},
            {"ingrid", "Ingrid Halvorsen", "OPERATIONS", "Director, Field Operations"},
            {"hal", "Harold Ferreira", "EXECUTIVE", "Chief Executive Officer"},
            {"sonia", "Sonia Brackett", "EXECUTIVE", "Chief Operating Officer"},
            // supporting cast
            {"vikram", "Vikram Desai", "FINANCE", "Chief Financial Officer"},
            {"tobias", "Tobias Nkemelu", "LEGAL", "Paralegal"},
            {"grace", "Grace Lindqvist", "ENGINEERING", "VP, Engineering"},
            {"amir", "Amir Haddad", "ENGINEERING", "Principal Engineer"},
            {"noor", "Noor Al-Salem", "ENGINEERING", "Staff Engineer, Platform"},
            {"conor", "Conor Blakeley", "ENGINEERING", "Site Reliability Engineer"},
            {"yuki", "Yuki Tanabe", "ENGINEERING", "QA Lead"},
            {"marisol", "Marisol Vega", "SALES", "Account Executive"},
            {"bethany", "Bethany Okonkwo", "FINANCE", "Senior Financial Analyst"},
            {"raj", "Rajeev Menon", "FINANCE", "Controller"},
            {"paulo", "Paulo Amorim", "OPERATIONS", "Logistics Coordinator"},
            {"tanya", "Tanya Whitcombe", "OPERATIONS", "Procurement Specialist"},
            {"joachim", "Joachim Restrepo", "HR", "Head of People"},
            {"elise", "Elise Farrow", "HR", "HR Business Partner"},
            {"kwame", "Kwame Boateng", "ENGINEERING", "Security Engineer"},
    };

    /** Everyone the planted narrative needs; the roster can never be trimmed below this. */
    static final int MIN = 11;
    static final int MAX = PEOPLE.length;

    private final List<Custodian> custodians = new ArrayList<>();
    private final Map<String, Custodian> byKey = new LinkedHashMap<>();

    Roster(int size) {
        if (size < MIN || size > MAX) {
            throw new IllegalArgumentException("custodian count must be between " + MIN + " and " + MAX);
        }
        for (int i = 0; i < size; i++) {
            String[] p = PEOPLE[i];
            Custodian c = new Custodian(
                    String.format(Locale.ROOT, "cust-%03d", i + 1), p[1], email(p[1]), p[2], p[3]);
            custodians.add(c);
            byKey.put(p[0], c);
        }
    }

    List<Custodian> all() {
        return List.copyOf(custodians);
    }

    Custodian get(int index) {
        return custodians.get(index);
    }

    /** Look up a narrative principal by short key. */
    Custodian key(String shortKey) {
        Custodian c = byKey.get(shortKey);
        if (c == null) {
            throw new IllegalArgumentException("unknown custodian key: " + shortKey);
        }
        return c;
    }

    int size() {
        return custodians.size();
    }

    /** Everyone in a department, in roster order. */
    List<Custodian> inDepartment(String department) {
        return custodians.stream().filter(c -> c.department().equals(department)).toList();
    }

    private static String email(String displayName) {
        String[] parts = displayName.split(" ");
        String local = ascii(parts[0]) + "." + ascii(parts[parts.length - 1]);
        return local.toLowerCase(Locale.ROOT) + "@" + DOMAIN;
    }

    private static String ascii(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("[^A-Za-z]", "");
    }
}
