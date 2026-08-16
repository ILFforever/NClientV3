package com.maxwai.nclientv3.settings;

import static com.maxwai.nclientv3.settings.FavoriteSyncManager.WriteOutcome;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.maxwai.nclientv3.settings.FavoriteSyncManager.Plan;
import com.maxwai.nclientv3.settings.FavoriteSyncManager.Tally;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Covers the two pieces of favorite sync that decide whether data is kept or destroyed, and that
 * a build which only compiles cannot check: which side each id belongs on, and whether a write
 * actually succeeded.
 */
public class FavoriteSyncManagerTest {

    private static Set<Integer> ids(int... values) {
        Set<Integer> set = new HashSet<>();
        for (int value : values) set.add(value);
        return set;
    }

    private static List<Integer> list(int... values) {
        Integer[] boxed = new Integer[values.length];
        for (int i = 0; i < values.length; i++) boxed[i] = values[i];
        return Arrays.asList(boxed);
    }

    private static Plan withBaseline(Set<Integer> remote, List<Integer> local, Set<Integer> baseline) {
        return FavoriteSyncManager.plan(remote, local, baseline, true);
    }

    // The branch table from the FavoriteSyncManager class comment, one case per row.

    @Test
    public void addedOnBothSidesIsLeftAlone() {
        Plan plan = withBaseline(ids(1), list(1), ids());
        assertTrue(plan.toDownload.isEmpty());
        assertTrue(plan.toUpload.isEmpty());
        assertTrue(plan.toDeleteLocal.isEmpty());
        assertTrue(plan.toDeleteRemote.isEmpty());
    }

    @Test
    public void localOnlyAndUnknownToBaselineIsUploaded() {
        Plan plan = withBaseline(ids(), list(1), ids());
        assertEquals(list(1), plan.toUpload);
        assertTrue(plan.toDeleteLocal.isEmpty());
    }

    @Test
    public void remoteOnlyAndUnknownToBaselineIsDownloaded() {
        Plan plan = withBaseline(ids(1), list(), ids());
        assertEquals(list(1), plan.toDownload);
        assertTrue(plan.toDeleteRemote.isEmpty());
    }

    @Test
    public void presentEverywhereIsLeftAlone() {
        Plan plan = withBaseline(ids(1), list(1), ids(1));
        assertTrue(plan.toDownload.isEmpty());
        assertTrue(plan.toUpload.isEmpty());
        assertTrue(plan.toDeleteLocal.isEmpty());
        assertTrue(plan.toDeleteRemote.isEmpty());
    }

    @Test
    public void goneFromRemoteButInBaselineIsDeletedLocally() {
        Plan plan = withBaseline(ids(), list(1), ids(1));
        assertEquals(list(1), plan.toDeleteLocal);
        assertTrue("removed on the web must not be re-uploaded", plan.toUpload.isEmpty());
    }

    @Test
    public void goneFromLocalButInBaselineIsDeletedRemotely() {
        Plan plan = withBaseline(ids(1), list(), ids(1));
        assertEquals(list(1), plan.toDeleteRemote);
        assertTrue("unfavorited in the app must not be re-downloaded", plan.toDownload.isEmpty());
    }

    @Test
    public void goneFromBothSidesIsNotResurrected() {
        Plan plan = withBaseline(ids(), list(), ids(1));
        assertTrue(plan.toDownload.isEmpty());
        assertTrue(plan.toUpload.isEmpty());
        assertTrue(plan.toDeleteLocal.isEmpty());
        assertTrue(plan.toDeleteRemote.isEmpty());
    }

    /**
     * Without a baseline, additions and removals are indistinguishable, so the plan has to be a
     * union. Deleting anything here would wipe a library on the very first sync.
     */
    @Test
    public void firstSyncUnionsAndDeletesNothing() {
        Plan plan = FavoriteSyncManager.plan(ids(1, 2), list(3, 4), ids(1, 3), false);
        assertTrue(plan.toDeleteLocal.isEmpty());
        assertTrue(plan.toDeleteRemote.isEmpty());
        assertEquals(ids(1, 2), new HashSet<>(plan.toDownload));
        assertEquals(list(3, 4), plan.toUpload);
    }

    @Test
    public void uploadsGoOutOldestFirst() {
        Plan plan = withBaseline(ids(), list(10, 20, 30), ids());
        assertEquals(list(10, 20, 30), plan.toUpload);
    }

    @Test
    public void baselineIsIgnoredEntirelyWithoutOne() {
        // Same inputs as goneFromLocalButInBaselineIsDeletedRemotely, minus the baseline flag.
        Plan plan = FavoriteSyncManager.plan(ids(1), list(), ids(1), false);
        assertEquals(list(1), plan.toDownload);
        assertTrue(plan.toDeleteRemote.isEmpty());
    }

    /**
     * The regression this file exists for. A successful upload was being counted as a failure,
     * and since runSync withholds the baseline whenever {@code failed > 0}, that quietly disabled
     * deletion reconciliation for good - with no error anywhere to show for it.
     */
    @Test
    public void successfulUploadIsNotAFailure() {
        Tally tally = new Tally();
        tally.recordUpload(WriteOutcome.OK);
        assertEquals(1, tally.uploaded);
        assertEquals(0, tally.failed);
    }

    @Test
    public void uploadOfAGalleryGoneUpstreamIsNotAFailure() {
        Tally tally = new Tally();
        tally.recordUpload(WriteOutcome.GONE);
        assertEquals(1, tally.dropped);
        assertEquals(0, tally.uploaded);
        assertEquals("dropping a 404 is a resolution, not a failure", 0, tally.failed);
    }

    @Test
    public void rejectedUploadIsAFailure() {
        Tally tally = new Tally();
        tally.recordUpload(WriteOutcome.FAILED);
        assertEquals(1, tally.failed);
        assertEquals(0, tally.uploaded);
    }

    @Test
    public void remoteDeleteOfAGalleryAlreadyGoneCounts() {
        Tally tally = new Tally();
        tally.recordRemoteDelete(WriteOutcome.GONE);
        assertEquals(1, tally.removedRemote);
        assertEquals(0, tally.failed);
    }

    @Test
    public void rejectedRemoteDeleteIsAFailure() {
        Tally tally = new Tally();
        tally.recordRemoteDelete(WriteOutcome.FAILED);
        assertEquals(1, tally.failed);
        assertEquals(0, tally.removedRemote);
    }

    /**
     * A clean run must leave {@code failed} at zero, because that is the sole condition under
     * which the baseline advances.
     */
    @Test
    public void aFullySuccessfulRunClearsTheWayForTheBaseline() {
        Tally tally = new Tally();
        for (int i = 0; i < 5; i++) tally.recordUpload(WriteOutcome.OK);
        for (int i = 0; i < 3; i++) tally.recordRemoteDelete(WriteOutcome.OK);
        tally.recordUpload(WriteOutcome.GONE);
        assertEquals(0, tally.failed);
        assertEquals(5, tally.uploaded);
        assertEquals(3, tally.removedRemote);
    }

    @Test
    public void planTouchesNothingWhenBothSidesAreEmpty() {
        Plan plan = withBaseline(Collections.emptySet(), Collections.emptyList(), ids(1, 2, 3));
        assertTrue(plan.toDownload.isEmpty());
        assertTrue(plan.toUpload.isEmpty());
        assertTrue(plan.toDeleteLocal.isEmpty());
        assertTrue(plan.toDeleteRemote.isEmpty());
    }
}
