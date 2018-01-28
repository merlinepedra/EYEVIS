
package org.tensorflow.demo;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Trace;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.StringTokenizer;
import org.tensorflow.Graph;
import org.tensorflow.Operation;
import org.tensorflow.contrib.android.TensorFlowInferenceInterface;
import org.tensorflow.demo.env.Logger;


public class TensorFlowMultiBoxDetector implements Classifier {
  private static final Logger LOGGER = new Logger();

  private static final int MAX_RESULTS = Integer.MAX_VALUE;

  private String inputName;
  private int inputSize;
  private int imageMean;
  private float imageStd;

  private int[] intValues;
  private float[] floatValues;
  private float[] outputLocations;
  private float[] outputScores;
  private String[] outputNames;
  private int numLocations;

  private boolean logStats = false;

  private TensorFlowInferenceInterface inferenceInterface;

  private float[] boxPriors;

  
  public static Classifier create(
      final AssetManager assetManager,
      final String modelFilename,
      final String locationFilename,
      final int imageMean,
      final float imageStd,
      final String inputName,
      final String outputLocationsName,
      final String outputScoresName) {
    final TensorFlowMultiBoxDetector d = new TensorFlowMultiBoxDetector();

    d.inferenceInterface = new TensorFlowInferenceInterface(assetManager, modelFilename);

    final Graph g = d.inferenceInterface.graph();

    d.inputName = inputName;
    
    final Operation inputOp = g.operation(inputName);
    if (inputOp == null) {
      throw new RuntimeException("Failed to find input Node '" + inputName + "'");
    }
    d.inputSize = (int) inputOp.output(0).shape().size(1);
    d.imageMean = imageMean;
    d.imageStd = imageStd;
   
    final Operation outputOp = g.operation(outputScoresName);
    if (outputOp == null) {
      throw new RuntimeException("Failed to find output Node '" + outputScoresName + "'");
    }
    d.numLocations = (int) outputOp.output(0).shape().size(1);

    d.boxPriors = new float[d.numLocations * 8];

    try {
      d.loadCoderOptions(assetManager, locationFilename, d.boxPriors);
    } catch (final IOException e) {
      throw new RuntimeException("Error initializing box priors from " + locationFilename);
    }

    
    d.outputNames = new String[] {outputLocationsName, outputScoresName};
    d.intValues = new int[d.inputSize * d.inputSize];
    d.floatValues = new float[d.inputSize * d.inputSize * 3];
    d.outputScores = new float[d.numLocations];
    d.outputLocations = new float[d.numLocations * 4];

    return d;
  }

  private TensorFlowMultiBoxDetector() {}

  private void loadCoderOptions(
      final AssetManager assetManager, final String locationFilename, final float[] boxPriors)
      throws IOException {
    final String assetPrefix = "file:///android_asset/";
    InputStream is;
    if (locationFilename.startsWith(assetPrefix)) {
      is = assetManager.open(locationFilename.split(assetPrefix)[1]);
    } else {
      is = new FileInputStream(locationFilename);
    }

    final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
    int priorIndex = 0;
    String line;
    while ((line = reader.readLine()) != null) {
      final StringTokenizer st = new StringTokenizer(line, ", ");
      while (st.hasMoreTokens()) {
        final String token = st.nextToken();
        try {
          final float number = Float.parseFloat(token);
          boxPriors[priorIndex++] = number;
        } catch (final NumberFormatException e) {
          // Silently ignore.
        }
      }
    }
    if (priorIndex != boxPriors.length) {
      throw new RuntimeException(
          "BoxPrior length mismatch: " + priorIndex + " vs " + boxPriors.length);
    }
  }

  private float[] decodeLocationsEncoding(final float[] locationEncoding) {
    final float[] locations = new float[locationEncoding.length];
    boolean nonZero = false;
    for (int i = 0; i < numLocations; ++i) {
      for (int j = 0; j < 4; ++j) {
        final float currEncoding = locationEncoding[4 * i + j];
        nonZero = nonZero || currEncoding != 0.0f;

        final float mean = boxPriors[i * 8 + j * 2];
        final float stdDev = boxPriors[i * 8 + j * 2 + 1];
        float currentLocation = currEncoding * stdDev + mean;
        currentLocation = Math.max(currentLocation, 0.0f);
        currentLocation = Math.min(currentLocation, 1.0f);
        locations[4 * i + j] = currentLocation;
      }
    }

    if (!nonZero) {
      LOGGER.w("No non-zero encodings; check log for inference errors.");
    }
    return locations;
  }

  private float[] decodeScoresEncoding(final float[] scoresEncoding) {
    final float[] scores = new float[scoresEncoding.length];
    for (int i = 0; i < scoresEncoding.length; ++i) {
      scores[i] = 1 / ((float) (1 + Math.exp(-scoresEncoding[i])));
    }
    return scores;
  }

  @Override
  public List<Recognition> recognizeImage(final Bitmap bitmap) {
    
    Trace.beginSection("recognizeImage");

    Trace.beginSection("preprocessBitmap");
    
    bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

    for (int i = 0; i < intValues.length; ++i) {
      floatValues[i * 3 + 0] = (((intValues[i] >> 16) & 0xFF) - imageMean) / imageStd;
      floatValues[i * 3 + 1] = (((intValues[i] >> 8) & 0xFF) - imageMean) / imageStd;
      floatValues[i * 3 + 2] = ((intValues[i] & 0xFF) - imageMean) / imageStd;
    }
    Trace.endSection(); 
	
    Trace.beginSection("feed");
    inferenceInterface.feed(inputName, floatValues, 1, inputSize, inputSize, 3);
    Trace.endSection();

    Trace.beginSection("run");
    inferenceInterface.run(outputNames, logStats);
    Trace.endSection();

    Trace.beginSection("fetch");
    final float[] outputScoresEncoding = new float[numLocations];
    final float[] outputLocationsEncoding = new float[numLocations * 4];
    inferenceInterface.fetch(outputNames[0], outputLocationsEncoding);
    inferenceInterface.fetch(outputNames[1], outputScoresEncoding);
    Trace.endSection();

    outputLocations = decodeLocationsEncoding(outputLocationsEncoding);
    outputScores = decodeScoresEncoding(outputScoresEncoding);

    final PriorityQueue<Recognition> pq =
        new PriorityQueue<Recognition>(
            1,
            new Comparator<Recognition>() {
              @Override
              public int compare(final Recognition lhs, final Recognition rhs) {
                
                return Float.compare(rhs.getConfidence(), lhs.getConfidence());
              }
            });

    for (int i = 0; i < outputScores.length; ++i) {
      final RectF detection =
          new RectF(
              outputLocations[4 * i] * inputSize,
              outputLocations[4 * i + 1] * inputSize,
              outputLocations[4 * i + 2] * inputSize,
              outputLocations[4 * i + 3] * inputSize);
      pq.add(new Recognition("" + i, null, outputScores[i], detection));
    }

    final ArrayList<Recognition> recognitions = new ArrayList<Recognition>();
    for (int i = 0; i < Math.min(pq.size(), MAX_RESULTS); ++i) {
      recognitions.add(pq.poll());
    }
    Trace.endSection();
    return recognitions;
  }

  @Override
  public void enableStatLogging(final boolean logStats) {
    this.logStats = logStats;
  }

  @Override
  public String getStatString() {
    return inferenceInterface.getStatString();
  }

  @Override
  public void close() {
    inferenceInterface.close();
  }
}