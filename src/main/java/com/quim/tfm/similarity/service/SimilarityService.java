package com.quim.tfm.similarity.service;

import com.quim.tfm.similarity.entity.Requirement;
import com.quim.tfm.similarity.model.Duplicate;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Service
public class SimilarityService {

    private static final Logger logger = LoggerFactory.getLogger(SimilarityService.class);

    private static final String FIELD_NAME = "NLP-PREPROCESS";
    private static final String STANDARD = "standard";
    private static final String STOPWORD = "stop";
    private static final String STEM = "porterstem";
    private static final String LOWERCASE = "lowercase";

    private static final CharSequence[] specialChars = {"\\n","\\t","\\r"};

    private static final double K1 = 2.0;
    private static final double K3 = 0.382;

    private static final double WF1 = 1.163;
    private static final double WF2 = 0.013;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private IDFService idfService;

    private Analyzer analyzer;

    public SimilarityService() {
        try {
            analyzer = CustomAnalyzer.builder()
                    .withTokenizer(STANDARD)
                    .addTokenFilter(STOPWORD)
                    .addTokenFilter(STEM)
                    .addTokenFilter(LOWERCASE)
                    .build();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void preprocessRequirementList(List<Requirement> requirements) {
        for (Requirement r : requirements) {
            bm25fPreprocess(r);
        }
    }

    public List<Duplicate> bm25f_req(Requirement requirement, int k) {
        logger.info("Init BM25f Preprocess for requirement " + requirement.getId());
        bm25fPreprocess(requirement);

        List<Requirement> requirements = requirementService.getRequirements();
        requirements.add(requirement);

        HashMap<String, Integer> summaryDocumentFrequency = idfService.getDocumentFrequency(requirements);

        List<Duplicate> topDuplicates = new ArrayList<>();

        for (Requirement compReq : requirements) {
            if (!compReq.getId().equals(requirement.getId())) {
                double bm25f_score = bm25f_textPair(requirements, requirement, compReq,
                        summaryDocumentFrequency, requirements.size());
                //TODO add other features

                double score = bm25f_score;

                if (topDuplicates.size() < k || topDuplicates.get(k-1).getScore() < score) {
                    Duplicate duplicate = new Duplicate(requirement.getId(), compReq.getId(), score);
                    insertNewTopDuplicate(topDuplicates, duplicate, k);
                }
            }
        }

        logger.info("Finished BM25f");
        return topDuplicates;

    }

    private void insertNewTopDuplicate(List<Duplicate> topDuplicates, Duplicate duplicate, int k) {
        boolean posFound = false;
        int i = 0;
        while (!posFound && i < k) {
            if (i == topDuplicates.size() ||
                    topDuplicates.get(i).getScore() < duplicate.getScore()) {
                posFound = true;
            } else ++i;
        }
        topDuplicates.add(i, duplicate);
        if (topDuplicates.size() > k) topDuplicates.remove(k);
    }

    private List<String> getCollect(Requirement req1, Requirement req2, int n) {

        String[] req1SummaryTokens = getNGrams(req1.getSummaryTokens(), n);
        String[] req1DescriptionTokens = getNGrams(req1.getDescriptionTokens(), n);
        String[] req2SummaryTokens = getNGrams(req2.getSummaryTokens(), n);
        String[] req2DescriptionTokens = getNGrams(req2.getDescriptionTokens(), n);

        String[] req1Tokens = Arrays.stream(
                Stream.of(req1SummaryTokens, req1DescriptionTokens)
                        .flatMap(Stream::of)
                        .toArray(String[]::new))
                .distinct().toArray(String[]::new);
        String[] req2Tokens = Arrays.stream(
                Stream.of(req2SummaryTokens, req2DescriptionTokens)
                        .flatMap(Stream::of)
                        .toArray(String[]::new))
                .distinct().toArray(String[]::new);
        return Arrays.stream(req1Tokens).distinct()
                .filter(Arrays.asList(req2Tokens)::contains)
                .collect(Collectors.toList());
    }

    private String[] getNGrams(String[] tokens, int n) {
       List<String> ngrams = new ArrayList<>();
        for (int i = 1; i < tokens.length; ++i) {
            String ngram = tokens[i-n+1];
            for (int j = 1; j < n; ++j) {
                ngram += " " + tokens[i-n+1+j];
            }
            ngrams.add(ngram);
        }
        return ngrams.stream().toArray(String[]::new);
    }

    private double bm25f_textPair(List<Requirement> requirements, Requirement req1, Requirement req2,
                                  HashMap<String, Integer> documentFrequency, int corpusSize) {
        List<String> unigramIntersection = getCollect(req1, req2, 1);
        List<String> bigramIntersection = getCollect(req1, req2, 2);

        double resUnigram = computeScore(requirements, req1, req2, documentFrequency, corpusSize, unigramIntersection);
        double resBigram = computeScore(requirements, req1, req2, documentFrequency, corpusSize, bigramIntersection);

        return resUnigram * WF1 + resBigram * WF2;
    }

    private double computeScore(List<Requirement> requirements, Requirement req1, Requirement req2,
                                HashMap<String, Integer> documentFrequency, int corpusSize, List<String> intersection) {
        double score = 0.0;
        for (String term : intersection) {
            double idf = idfService.idf(term, documentFrequency, corpusSize);
            double tf = idfService.tf(term, requirements, req1);
            double tfd = tf / (K1 + tf);
            double tfq = idfService.tfq(term, req2);
            double wq = (K3 + 1.0) * tfq / (K3 + tfq);
            score += idf * tfd * wq;
        }
        return score;
    }

    private void bm25fPreprocess(Requirement r) {
        r.setSummaryTokens(analyze(r.getSummary()).stream().toArray(String[]::new));
        r.setDescriptionTokens(analyze(r.getDescription()).stream().toArray(String[]::new));
    }

    private List<String> analyze(String text) {
        List<String> tokens = new ArrayList<>();
        try {
            for (CharSequence cs : specialChars)
                text = text.replace(cs, " ");
            TokenStream tokenStream = analyzer.tokenStream(FIELD_NAME, text);
            CharTermAttribute attr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();

            while (tokenStream.incrementToken()) {
                tokens.add(attr.toString());
            }
            tokenStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return tokens;
    }
}