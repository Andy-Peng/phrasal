package edu.stanford.nlp.mt.decoder.feat.base;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import edu.stanford.nlp.mt.decoder.feat.DerivationFeaturizer;
import edu.stanford.nlp.mt.decoder.feat.RuleFeaturizer;
import edu.stanford.nlp.mt.decoder.feat.FeatureUtils;
import edu.stanford.nlp.mt.lm.LMState;
import edu.stanford.nlp.mt.lm.LanguageModel;
import edu.stanford.nlp.mt.lm.LanguageModelFactory;
import edu.stanford.nlp.mt.util.AbstractWordClassMap;
import edu.stanford.nlp.mt.util.FeatureValue;
import edu.stanford.nlp.mt.util.Featurizable;
import edu.stanford.nlp.mt.util.IString;
import edu.stanford.nlp.mt.util.Sequence;
import edu.stanford.nlp.mt.util.Sequences;
import edu.stanford.nlp.mt.util.ArraySequence;
import edu.stanford.nlp.mt.util.TargetClassMap;
import edu.stanford.nlp.util.PropertiesUtils;

/**
 * Featurizer for n-gram language models.
 * 
 * @author danielcer
 * @author Spence Green
 */
public class NGramLanguageModelFeaturizer extends DerivationFeaturizer<IString, String> implements
RuleFeaturizer<IString, String> {
  private static final boolean DEBUG = false;
  public static final String DEFAULT_FEATURE_NAME = "LM";

  private final String featureName;
  private final LanguageModel<IString> lm;
  private final IString startToken;
  private final IString endToken;

  private final boolean isClassBased;
  private final AbstractWordClassMap targetClassMap;

  private static final boolean wrapBoundary = System.getProperties().containsKey("wrapBoundary");
  
  // With prefix-constrained decoding we only need to compute LM score once
  // the states are cached in prefixDecodingCache
  private List<LMState> prefixDecodingCache = null;
  private int nextUncachedPosition = 0;
  private boolean useCache = false;

  /**
   * Constructor.
   * 
   * @param lm
   */
  public NGramLanguageModelFeaturizer(LanguageModel<IString> lm) {
    this.lm = lm;
    featureName = DEFAULT_FEATURE_NAME;
    this.startToken = lm.getStartToken();
    this.endToken = lm.getEndToken();
    this.isClassBased = false;
    this.targetClassMap = null;
  }

  /**
   * Constructor called by Phrasal when NGramLanguageModelFeaturizer appears in
   * <code>Phrasal.LANGUAGE_MODEL_OPT</code>.
   * 
   * The first argument is always the language model filename and the second
   * argument is always the feature name.
   * 
   * Additional arguments are named parameters.
   */
  public NGramLanguageModelFeaturizer(String...args) throws IOException {
    if (args.length < 2) {
      throw new RuntimeException(
          "At least two arguments are needed: LM file name and LM feature name");
    }
    // Load the LM
    this.lm = LanguageModelFactory.load(args[0]);
    this.startToken = lm.getStartToken();
    this.endToken = lm.getEndToken();

    // Set the feature name
    this.featureName = args[1];

    // Named parameters
    Properties options = FeatureUtils.argsToProperties(args);
    this.isClassBased = PropertiesUtils.getBool(options, "classBased", false);
    if (isClassBased && options.containsKey("classMap")) {
      // A local class map that differs from the one specified by Phrasal.TARGET_CLASS_MAP
      this.targetClassMap = new LocalTargetMap();
      this.targetClassMap.load(options.getProperty("classMap"));
    } else if (isClassBased) {
      this.targetClassMap = TargetClassMap.getInstance();
    } else {
      this.targetClassMap = null;
    }    
  }

  /**
   * Convert a lexical n-gram to a class-based n-gram.
   * 
   * @param targetSequence
   * @return
   */
  private Sequence<IString> toClassRepresentation(Sequence<IString> targetSequence) {
    if (targetSequence.size() == 0) return targetSequence;
    
    IString[] array = new IString[targetSequence.size()];
    for (int i = 0; i < array.length; ++i) {
      if (wrapBoundary && (targetSequence.get(i).equals(this.startToken) || targetSequence.get(i).equals(this.endToken)))
        array[i] = targetSequence.get(i);
      else
        array[i] = targetClassMap.get(targetSequence.get(i));
    }
    return new ArraySequence<IString>(true, array);
  }

  
  private LMState score(LMState priorState, Sequence<IString> targetSequence, boolean isSentBegin, boolean isSentEnd, Featurizable<IString, String> f) {
    
    Sequence<IString> partialTranslation = isClassBased ? 
        toClassRepresentation(targetSequence) : targetSequence;
      
    int startIndex = 0;
    if (! wrapBoundary) {
      if (isSentBegin && isSentEnd) {
        partialTranslation = Sequences.wrapStartEnd(
            partialTranslation, startToken, endToken);
        startIndex = 1;
      } else if (isSentBegin) {
        partialTranslation = Sequences.wrapStart(partialTranslation, startToken);
        startIndex = 1;
      } else if (isSentEnd) {
        partialTranslation = Sequences.wrapEnd(partialTranslation, endToken);
      } 
    } else if (isSentBegin) {
      if (partialTranslation.size() < 2) return null;
      startIndex = 1;
    } else if (!isSentBegin && priorState == null) {
      partialTranslation = Sequences.wrapStart(partialTranslation, f.prior.targetPrefix.get(0));
      startIndex = 1;
    }
    
    return lm.score(partialTranslation, startIndex, priorState);
  }
  
  
  private void fillPrefixCache(Featurizable<IString, String> f) {
    int prefixLength = f.derivation.prefixLength;

    if(prefixDecodingCache == null) {
      prefixDecodingCache = new ArrayList<>(f.derivation.prefixLength);
      for(int i = 0; i < prefixLength; i++)
        prefixDecodingCache.add(null);
    }
    
    int lastCachePosition = Math.min(f.targetPrefix.size(), prefixLength) - 1;
    
    if(lastCachePosition < nextUncachedPosition) // cache is already filled
      return;
    
    LMState state = null;
    if(nextUncachedPosition > 0)
      state = prefixDecodingCache.get(nextUncachedPosition - 1);
    
    for(int i = nextUncachedPosition; i <= lastCachePosition; ++i) {
      state = score(state, f.targetPrefix.subsequence(i, i+1),
          i == 0, false, f); // do not consider sentence end score
      prefixDecodingCache.set(i, state);
    }
    
    nextUncachedPosition = lastCachePosition + 1;
  }
  
  @Override
  public List<FeatureValue<String>> featurize(Featurizable<IString, String> f) {
    if (DEBUG) {
      System.err.printf("Sequence: %s%n\tNovel Phrase: %s%n",
          f.targetPrefix, f.targetPhrase);
      System.err.printf("Untranslated tokens: %d%n", f.numUntranslatedSourceTokens);
      System.err.println("ngram scoring:");
    }
    
    if(useCache && f.derivation.prefixLength > 0) fillPrefixCache(f);
      
    double score = 0.0;
    LMState state = null;
    
    if(!useCache || f.targetPosition >= nextUncachedPosition) {
      // normal score computation
      LMState priorState = f.prior == null ? null : (LMState) f.prior.getState(this);
      state = score(priorState, f.targetPhrase, f.prior == null, f.done, f);
      score = state.getScore();
    }
    else { // use the cache
      int cacheMax = Math.min(nextUncachedPosition, f.targetPrefix.size());
      for(int i = f.targetPosition; i < cacheMax; ++i) {
        state = prefixDecodingCache.get(i);
        score += state.getScore();
      }
      
      // if the phrase straddles the cache boundary
      if(f.targetPrefix.size() > nextUncachedPosition) {
        LMState priorState = prefixDecodingCache.get(cacheMax - 1);
        state = score(priorState, f.targetPrefix.subsequence(cacheMax, f.targetPrefix.size()), false, f.done, f);
        score += state.getScore();
      } 
      else if(f.done) { // add sentence end score
        state = score(state, f.targetPrefix.subsequence(0,0), false, f.done, f);
        score += state.getScore();
      }
      
    }
    
    f.setState(this, state);
    
    if (DEBUG) {
      System.err.printf("Final score: %f%n", state.getScore());
      System.err.println("===================");
    }
    
    return Collections.singletonList(new FeatureValue<String>(featureName, score));
  }

  @Override
  public List<FeatureValue<String>> ruleFeaturize(
      Featurizable<IString, String> f) {
    assert (f.targetPhrase != null);
    double lmScore = lm.score(f.targetPhrase, 0, null).getScore();
    return Collections.singletonList(new FeatureValue<String>(featureName, lmScore));
  }

  @Override
  public void initialize(int sourceInputId,
      Sequence<IString> foreign) {
  }

  @Override
  public void initialize() {}

  @Override
  public boolean isolationScoreOnly() {
    return true;
  }
  
  private static class LocalTargetMap extends AbstractWordClassMap {
    public LocalTargetMap() {
      wordToClass = new HashMap<>();
    }
  }
  
  public void startUsingCache() {
    prefixDecodingCache = null;
    nextUncachedPosition = 0;
    useCache = true;
  }
  
  // ALWAYS call stopUsingCache after finishing
  public void stopUsingCache() {
    prefixDecodingCache = null;
    nextUncachedPosition = 0;
    useCache = false;
  }
}
