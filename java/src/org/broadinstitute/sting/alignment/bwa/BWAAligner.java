package org.broadinstitute.sting.alignment.bwa;

import org.broadinstitute.sting.alignment.bwa.bwt.*;
import org.broadinstitute.sting.alignment.Alignment;
import org.broadinstitute.sting.alignment.Aligner;
import org.broadinstitute.sting.utils.BaseUtils;

import java.io.File;
import java.util.*;

import net.sf.samtools.SAMRecord;

/**
 * Create imperfect alignments from the read to the genome represented by the given BWT / suffix array. 
 *
 * @author mhanna
 * @version 0.1
 */
public class BWAAligner implements Aligner {
    /**
     * BWT in the forward direction.
     */
    private BWT forwardBWT;

    /**
     * BWT in the reverse direction.
     */
    private BWT reverseBWT;

    /**
     * Suffix array in the forward direction.
     */
    private SuffixArray forwardSuffixArray;

    /**
     * Suffix array in the reverse direction.
     */
    private SuffixArray reverseSuffixArray;

    /**
     * Maximum edit distance (-n option from original BWA).
     */
    private final int MAXIMUM_EDIT_DISTANCE = 4;

    /**
     * Maximum number of gap opens (-o option from original BWA).
     */
    private final int MAXIMUM_GAP_OPENS = 1;

    /**
     * Maximum number of gap extensions (-e option from original BWA).
     */
    private final int MAXIMUM_GAP_EXTENSIONS = 6;

    /**
     * Penalty for straight mismatches (-M option from original BWA).
     */
    public final int MISMATCH_PENALTY = 3;

    /**
     * Penalty for gap opens (-O option from original BWA).
     */
    public final int GAP_OPEN_PENALTY = 11;

    /**
     * Penalty for gap extensions (-E option from original BWA).
     */
    public final int GAP_EXTENSION_PENALTY = 4;

    /**
     * Skip the ends of indels.
     */
    public final int INDEL_END_SKIP = 5;

    public BWAAligner( File forwardBWTFile, File reverseBWTFile, File forwardSuffixArrayFile, File reverseSuffixArrayFile ) {
        forwardBWT = new BWTReader(forwardBWTFile).read();
        reverseBWT = new BWTReader(reverseBWTFile).read();
        forwardSuffixArray = new SuffixArrayReader(forwardSuffixArrayFile).read();
        reverseSuffixArray = new SuffixArrayReader(reverseSuffixArrayFile).read();
    }

    public List<Alignment> align( SAMRecord read ) {
        List<Alignment> successfulMatches = new ArrayList<Alignment>();

        byte[] uncomplementedBases = read.getReadBases();
        byte[] complementedBases = BaseUtils.reverse(BaseUtils.simpleReverseComplement(uncomplementedBases));

        List<LowerBound> forwardLowerBounds = LowerBound.create(uncomplementedBases,forwardBWT);
        List<LowerBound> reverseLowerBounds = LowerBound.create(complementedBases,reverseBWT);

        /*
        for( int i = 0; i < forwardLowerBounds.size(); i++ )
            System.out.printf("ForwardBWT: lb[%d] = %s%n",i,forwardLowerBounds.get(i));
        for( int i = 0; i < reverseLowerBounds.size(); i++ )
            System.out.printf("ReverseBWT: lb[%d] = %s%n",i,reverseLowerBounds.get(i));
            */

        // Seed the best score with any score that won't overflow on comparison.
        int bestScore = Integer.MAX_VALUE - MISMATCH_PENALTY;
        int bestDiff = MAXIMUM_EDIT_DISTANCE+1;
        int maxDiff = MAXIMUM_EDIT_DISTANCE;

        PriorityQueue<BWAAlignment> alignments = new PriorityQueue<BWAAlignment>();

        // Create a fictional initial alignment, with the position just off the end of the read, and the limits
        // set as the entire BWT.
        alignments.add(createSeedAlignment(reverseBWT));
        alignments.add(createSeedAlignment(forwardBWT));

        while(!alignments.isEmpty()) {
            BWAAlignment alignment = alignments.remove();

            // From bwtgap.c in the original BWT; if the rank is worse than the best score + the mismatch PENALTY, move on.
            if( alignment.getScore() > bestScore + MISMATCH_PENALTY )
                break;

            byte[] bases = alignment.negativeStrand ? complementedBases : uncomplementedBases;
            BWT bwt = alignment.negativeStrand ? forwardBWT : reverseBWT;
            List<LowerBound> lowerBounds = alignment.negativeStrand ? reverseLowerBounds : forwardLowerBounds;

            // Found a valid alignment; store it and move on.
            if(alignment.position == read.getReadLength()-1) {
                if( !alignment.isNegativeStrand() ) {
                    int sizeAlongReference = alignment.getNumberOfBasesMatchingState(AlignmentState.MATCH_MISMATCH)+alignment.getNumberOfBasesMatchingState(AlignmentState.DELETION);
                    alignment.alignmentStart = reverseBWT.length() - reverseSuffixArray.get(alignment.loBound) - sizeAlongReference + 1;
                }
                else
                    alignment.alignmentStart = forwardSuffixArray.get(alignment.loBound) + 1;
                successfulMatches.add(alignment);

                bestScore = Math.min(alignment.getScore(),bestScore);
                bestDiff = Math.min(alignment.mismatches+alignment.gapOpens+alignment.gapExtensions,bestDiff);
                maxDiff = bestDiff + 1;

                continue;
            }

            //System.out.printf("Processing alignments; queue size = %d, alignment = %s, bound = %d, base = %s%n", alignments.size(), alignment, lowerBounds.get(alignment.position+1).value, alignment.position >= 0 ? (char)bases[alignment.position] : "");

            // if z < D(i) then return {}
            int mismatches = maxDiff - alignment.mismatches - alignment.gapOpens - alignment.gapExtensions;            
            if( mismatches < lowerBounds.get(alignment.position+1).value )
                continue;

            if( mismatches > 0 &&
                alignment.position+1 >= INDEL_END_SKIP-1+alignment.gapOpens+alignment.gapExtensions &&
                read.getReadLength()-1-(alignment.position+1) >= INDEL_END_SKIP+alignment.gapOpens+alignment.gapExtensions ) {
                if( alignment.getCurrentState() == AlignmentState.MATCH_MISMATCH ) {
                    if( alignment.gapOpens < MAXIMUM_GAP_OPENS ) {
                        // Add a potential insertion extension.
                        BWAAlignment insertionAlignment = createInsertionAlignment(alignment);
                        insertionAlignment.gapOpens++;
                        alignments.add(insertionAlignment);

                        // Add a potential deletion by marking a deletion and augmenting the position.
                        List<BWAAlignment> deletionAlignments = createDeletionAlignments(bwt,alignment);
                        for( BWAAlignment deletionAlignment: deletionAlignments )
                            deletionAlignment.gapOpens++;
                        alignments.addAll(deletionAlignments);
                    }
                }
                else if( alignment.getCurrentState() == AlignmentState.INSERTION ) {
                    if( alignment.gapExtensions < MAXIMUM_GAP_EXTENSIONS && mismatches > 0 ) {
                        // Add a potential insertion extension.
                        BWAAlignment insertionAlignment = createInsertionAlignment(alignment);
                        insertionAlignment.gapExtensions++;
                        alignments.add(insertionAlignment);
                    }
                }
                else if( alignment.getCurrentState() == AlignmentState.DELETION ) {
                    if( alignment.gapExtensions < MAXIMUM_GAP_EXTENSIONS && mismatches > 0 ) {
                        // Add a potential deletion by marking a deletion and augmenting the position.
                        List<BWAAlignment> deletionAlignments = createDeletionAlignments(bwt,alignment);
                        for( BWAAlignment deletionAlignment: deletionAlignments )
                            deletionAlignment.gapExtensions++;
                        alignments.addAll(deletionAlignments);
                    }
                }
            }

            // Mismatches
            // Temporary -- look ahead to see if the next alignment is bounded.
            boolean allowMismatches = mismatches > 0;
            if( alignment.position+1 < read.getReadLength()-1 )
                allowMismatches &= 
                    !(lowerBounds.get(alignment.position+2).value == mismatches-1 && lowerBounds.get(alignment.position+1).value == mismatches-1 &&
                      lowerBounds.get(alignment.position+2).width == lowerBounds.get(alignment.position+1).width);
            alignments.addAll(createMatchedAlignments(bwt,alignment,bases,allowMismatches));
        }

        return successfulMatches;
    }

    /**
     * Create an seeding alignment to use as a starting point when traversing.
     * @param bwt source BWT.
     * @return Seed alignment.
     */
    private BWAAlignment createSeedAlignment(BWT bwt) {
        BWAAlignment seed = new BWAAlignment(this);
        seed.negativeStrand = (bwt == forwardBWT);
        seed.position = -1;
        seed.loBound = 0;
        seed.hiBound = bwt.length();
        seed.mismatches = 0;
        return seed;
    }

    /**
     * Creates a new alignments representing direct matches / mismatches.
     * @param bwt Source BWT with which to work.
     * @param alignment Alignment for the previous position.
     * @param bases The bases in the read.
     * @param allowMismatch Should mismatching bases be allowed?
     * @return New alignment representing this position if valid; null otherwise.
     */
    private List<BWAAlignment> createMatchedAlignments( BWT bwt, BWAAlignment alignment, byte[] bases, boolean allowMismatch ) {
        List<BWAAlignment> newAlignments = new ArrayList<BWAAlignment>();

        List<Base> baseChoices = new ArrayList<Base>();
        Base thisBase = Base.fromASCII(bases[alignment.position+1]);

        if( allowMismatch )
            baseChoices.addAll(EnumSet.allOf(Base.class));
        else
            baseChoices.add(thisBase);

        if( thisBase != null ) {
            // Keep rotating the current base to the last position until we've hit the current base.
            for( ;; ) {
                baseChoices.add(baseChoices.remove(0));
                if( thisBase.equals(baseChoices.get(baseChoices.size()-1)) )
                    break;

            }
        }

        for(Base base: baseChoices) {
            BWAAlignment newAlignment = alignment.clone();

            newAlignment.loBound = bwt.counts(base) + bwt.occurrences(base,alignment.loBound-1) + 1;
            newAlignment.hiBound = bwt.counts(base) + bwt.occurrences(base,alignment.hiBound);

            // If this alignment is valid, skip it.
            if( newAlignment.loBound > newAlignment.hiBound )
                continue;

            newAlignment.position++;
            newAlignment.addState(AlignmentState.MATCH_MISMATCH);
            if( base.toASCII() != bases[newAlignment.position] )
                newAlignment.mismatches++;

            newAlignments.add(newAlignment);
        }

        return newAlignments;
    }

    /**
     * Create a new alignment representing an insertion at this point in the read.
     * @param alignment Alignment from which to derive the insertion.
     * @return New alignment reflecting the insertion.
     */
    private BWAAlignment createInsertionAlignment( BWAAlignment alignment ) {
        // Add a potential insertion extension.
        BWAAlignment newAlignment = alignment.clone();
        newAlignment.position++;
        newAlignment.addState(AlignmentState.INSERTION);
        return newAlignment;
    }

    /**
     * Create new alignments representing a deletion at this point in the read.
     * @param bwt source BWT for inferring deletion info.
     * @param alignment Alignment from which to derive the deletion.
     * @return New alignments reflecting all possible deletions.
     */
    private List<BWAAlignment> createDeletionAlignments( BWT bwt, BWAAlignment alignment) {
        List<BWAAlignment> newAlignments = new ArrayList<BWAAlignment>();
        for(Base base: EnumSet.allOf(Base.class)) {
            BWAAlignment newAlignment = alignment.clone();

            newAlignment.loBound = bwt.counts(base) + bwt.occurrences(base,alignment.loBound-1) + 1;
            newAlignment.hiBound = bwt.counts(base) + bwt.occurrences(base,alignment.hiBound);

            // If this alignment is valid, skip it.
            if( newAlignment.loBound > newAlignment.hiBound )
                continue;

            newAlignment.addState(AlignmentState.DELETION);

            newAlignments.add(newAlignment);
        }

        return newAlignments;
    }

}
