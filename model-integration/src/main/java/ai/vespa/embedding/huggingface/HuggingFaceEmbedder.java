// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.embedding.huggingface;

import ai.vespa.embedding.PoolingStrategy;
import ai.vespa.modelintegration.evaluator.OnnxEvaluator;
import ai.vespa.modelintegration.evaluator.OnnxEvaluatorOptions;
import ai.vespa.modelintegration.evaluator.OnnxRuntime;
import com.yahoo.api.annotations.Beta;
import com.yahoo.component.AbstractComponent;
import com.yahoo.component.annotation.Inject;
import com.yahoo.embedding.huggingface.HuggingFaceEmbedderConfig;
import com.yahoo.language.huggingface.HuggingFaceTokenizer;
import com.yahoo.language.process.Embedder;
import com.yahoo.tensor.IndexedTensor;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorAddress;
import com.yahoo.tensor.TensorType;

import java.nio.file.Paths;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.yahoo.language.huggingface.ModelInfo.TruncationStrategy.LONGEST_FIRST;

@Beta
public class HuggingFaceEmbedder extends AbstractComponent implements Embedder {

    private static final Logger log = Logger.getLogger(HuggingFaceEmbedder.class.getName());

    private final Embedder.Runtime runtime;
    private final String inputIdsName;
    private final String attentionMaskName;
    private final String tokenTypeIdsName;
    private final String outputName;
    private final boolean normalize;
    private final HuggingFaceTokenizer tokenizer;
    private final OnnxEvaluator evaluator;
    private final PoolingStrategy poolingStrategy;

    @Inject
    public HuggingFaceEmbedder(OnnxRuntime onnx, Embedder.Runtime runtime, HuggingFaceEmbedderConfig config) {
        this.runtime = runtime;
        inputIdsName = config.transformerInputIds();
        attentionMaskName = config.transformerAttentionMask();
        tokenTypeIdsName = config.transformerTokenTypeIds();
        outputName = config.transformerOutput();
        normalize = config.normalize();
        var tokenizerPath = Paths.get(config.tokenizerPath().toString());
        var builder = new HuggingFaceTokenizer.Builder()
                .addSpecialTokens(true)
                .addDefaultModel(tokenizerPath)
                .setPadding(false);
        var info = HuggingFaceTokenizer.getModelInfo(tokenizerPath);
        log.fine(() -> "'%s' has info '%s'".formatted(tokenizerPath, info));
        if (info.maxLength() == -1 || info.truncation() != LONGEST_FIRST) {
            // Force truncation to max token vector length accepted by model if tokenizer.json contains no valid truncation configuration
            int maxLength = info.maxLength() > 0 && info.maxLength() <= config.transformerMaxTokens()
                    ? info.maxLength()
                    : config.transformerMaxTokens();
            builder.setTruncation(true).setMaxLength(maxLength);
        }
        this.tokenizer = builder.build();
        poolingStrategy = PoolingStrategy.fromString(config.poolingStrategy().toString());
        var onnxOpts = new OnnxEvaluatorOptions();
        if (config.transformerGpuDevice() >= 0)
            onnxOpts.setGpuDevice(config.transformerGpuDevice());
        onnxOpts.setExecutionMode(config.transformerExecutionMode().toString());
        onnxOpts.setThreads(config.transformerInterOpThreads(), config.transformerIntraOpThreads());
        evaluator = onnx.evaluatorOf(config.transformerModel().toString(), onnxOpts);
        validateModel();
    }

    public void validateModel() {
        Map<String, TensorType> inputs = evaluator.getInputInfo();
        validateName(inputs, inputIdsName, "input");
        validateName(inputs, attentionMaskName, "input");
        if (!tokenTypeIdsName.isEmpty()) validateName(inputs, tokenTypeIdsName, "input");

        Map<String, TensorType> outputs = evaluator.getOutputInfo();
        validateName(outputs, outputName, "output");
    }

    private void validateName(Map<String, TensorType> types, String name, String type) {
        if ( ! types.containsKey(name)) {
            throw new IllegalArgumentException("Model does not contain required " + type + ": '" + name + "'. " +
                    "Model contains: " + String.join(",", types.keySet()));
        }
    }

    @Override
    public List<Integer> embed(String s, Context context) {
        var start = System.nanoTime();
        var tokens = tokenizer.embed(s, context);
        runtime.sampleSequenceLength(tokens.size(), context);
        runtime.sampleEmbeddingLatency((System.nanoTime() - start)/1_000_000d, context);
        return tokens;
    }

    @Override
    public void deconstruct() {
        evaluator.close();
        tokenizer.close();
    }

    @Override
    public Tensor embed(String s, Context context, TensorType tensorType) {
        var start = System.nanoTime();
        var encoding = tokenizer.encode(s, context.getLanguage());
        runtime.sampleSequenceLength(encoding.ids().size(), context);
        Tensor inputSequence = createTensorRepresentation(encoding.ids(), "d1");
        Tensor attentionMask = createTensorRepresentation(encoding.attentionMask(), "d1");
        Tensor tokenTypeIds = tokenTypeIdsName.isEmpty() ? null : createTensorRepresentation(encoding.typeIds(), "d1");


        Map<String, Tensor> inputs;
        if (tokenTypeIdsName.isEmpty() || tokenTypeIds.isEmpty()) {
            inputs = Map.of(inputIdsName, inputSequence.expand("d0"),
                            attentionMaskName, attentionMask.expand("d0"));
        } else {
            inputs = Map.of(inputIdsName, inputSequence.expand("d0"),
                            attentionMaskName, attentionMask.expand("d0"),
                            tokenTypeIdsName, tokenTypeIds.expand("d0"));
        }

        Map<String, Tensor> outputs = evaluator.evaluate(inputs);
        IndexedTensor tokenEmbeddings = (IndexedTensor) outputs.get(outputName);
        long[] resultShape = tokenEmbeddings.shape();
        //shape batch, sequence, embedding dimensionality
        if (resultShape.length != 3) {
            throw new IllegalArgumentException("" +
                    "Expected 3 output dimensions for output name '" +
                    outputName + "': [batch, sequence, embedding], got " + resultShape.length);
        }
        Tensor result;
        if (tensorType.valueType() == TensorType.Value.INT8) {
            long outputDimensions = resultShape[2];
            long targetDim = tensorType.dimensions().get(0).size().get();

            if(targetDim * 8 > outputDimensions) {
                throw new IllegalArgumentException("Cannot pack " + outputDimensions + " into " + targetDim + " int8s");
            }
            //Dimensionality flexibility 🪆 - packing only the first 8*targetDim values from the model output
            long firstDimensions = 8 * targetDim;
            String name = tensorType.indexedSubtype().dimensions().get(0).name();
            //perform pooling and normalizing using floating point embeddings before binarizing
            //using the firstDimensions as the target dimensionality
            TensorType poolingType = new TensorType.Builder(TensorType.Value.FLOAT).indexed(name, firstDimensions).build();
            result = poolingStrategy.toSentenceEmbedding(poolingType, tokenEmbeddings, attentionMask);
            result = normalize? normalize(result, poolingType) : result;
            result = binarize((IndexedTensor) result, tensorType);

        } else { // regular floating points embeddings
            result = poolingStrategy.toSentenceEmbedding(tensorType, tokenEmbeddings, attentionMask);
            result = normalize ? normalize(result, tensorType) : result;
        }
        runtime.sampleEmbeddingLatency((System.nanoTime() - start)/1_000_000d, context);
        return result;
    }

    Tensor normalize(Tensor embedding, TensorType tensorType) {
        double sumOfSquares = 0.0;

        Tensor.Builder builder = Tensor.Builder.of(tensorType);
        for (int i = 0; i < tensorType.dimensions().get(0).size().get(); i++) {
            double item = embedding.get(TensorAddress.of(i));
            sumOfSquares += item * item;
        }

        double magnitude = Math.sqrt(sumOfSquares);

        for (int i = 0; i < tensorType.dimensions().get(0).size().get(); i++) {
            double value = embedding.get(TensorAddress.of(i));
            builder.cell(value / magnitude, i);
        }

        return builder.build();
    }

    static public Tensor binarize(IndexedTensor embedding, TensorType tensorType) {
        Tensor.Builder builder = Tensor.Builder.of(tensorType);
        BitSet bitSet = new BitSet(8);
        int index = 0;
        for (int d = 0; d < embedding.sizeAsInt(); d++) {
            var value = embedding.get(d);
            int bitIndex = 7 - (d % 8);
            if (value > 0.0) {
                bitSet.set(bitIndex);
            } else {
                bitSet.clear(bitIndex);
            }
            if ((d + 1) % 8 == 0) {
                byte[] bytes = bitSet.toByteArray();
                byte packed = (bytes.length == 0) ? 0 : bytes[0];
                builder.cell(TensorAddress.of(index), packed);
                index++;
                bitSet = new BitSet(8);
            }
        }
        return builder.build();
    }

    private IndexedTensor createTensorRepresentation(List<Long> input, String dimension) {
        int size = input.size();
        TensorType type = new TensorType.Builder(TensorType.Value.FLOAT).indexed(dimension, size).build();
        IndexedTensor.Builder builder = IndexedTensor.Builder.of(type);
        for (int i = 0; i < size; ++i) {
            builder.cell(input.get(i), i);
        }
        return builder.build();
    }


}

