package com.ticketrush.event.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String venue;
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;
    @Column(name = "sale_opens_at", nullable = false)
    private Instant saleOpensAt;
    @Column(name = "sale_closes_at", nullable = false)
    private Instant saleClosesAt;
    @Column(name = "price_cents", nullable = false)
    private long priceCents;
    @Column(name = "total_seats", nullable = false)
    private int totalSeats;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Listing listing = Listing.INTERNAL;
    private String artist;
    @Enumerated(EnumType.STRING)
    private Category category;
    private String city;
    private String description;

    protected Event() {
    }

    public Event(String name, String venue, Instant startsAt, Instant saleOpensAt, Instant saleClosesAt,
                 long priceCents, int totalSeats, Instant createdAt) {
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.saleOpensAt = saleOpensAt;
        this.saleClosesAt = saleClosesAt;
        this.priceCents = priceCents;
        this.totalSeats = totalSeats;
        this.createdAt = createdAt;
    }

    /** Customer-facing details; all optional (no listing = INTERNAL). */
    public Event describedAs(Listing listing, String artist, Category category, String city, String description) {
        this.listing = listing == null ? Listing.INTERNAL : listing;
        this.artist = artist;
        this.category = category;
        this.city = city;
        this.description = description;
        return this;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getSaleOpensAt() { return saleOpensAt; }
    public Instant getSaleClosesAt() { return saleClosesAt; }
    public long getPriceCents() { return priceCents; }
    public int getTotalSeats() { return totalSeats; }
    public Listing getListing() { return listing; }
    public String getArtist() { return artist; }
    public Category getCategory() { return category; }
    public String getCity() { return city; }
    public String getDescription() { return description; }

    public boolean saleOpenAt(Instant now) {
        return !now.isBefore(saleOpensAt) && now.isBefore(saleClosesAt);
    }
}
