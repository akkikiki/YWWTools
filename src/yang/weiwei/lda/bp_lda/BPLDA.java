package yang.weiwei.lda.bp_lda;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import yang.weiwei.lda.LDA;
import yang.weiwei.lda.LDACfg;
import yang.weiwei.lda.LDAParam;
import yang.weiwei.lda.util.LDADoc;
import yang.weiwei.lda.util.LDAResult;
import yang.weiwei.util.IOUtil;

public class BPLDA extends LDA
{
	protected double _alpha;
	
	protected int numBlocks;
	protected int blockAssign[];
	protected int blockTopicCounts[][];
	protected int blockTokenCounts[];
	
	protected double pi[][];
	
	public void readBlocks(String blockFileName) throws IOException
	{
		BufferedReader br=new BufferedReader(new FileReader(blockFileName));
		String line,seg[];
		numBlocks=0;
		blockAssign=new int[numDocs];
		while ((line=br.readLine())!=null)
		{
			numBlocks++;
			seg=line.split(" ");
			for (int i=0; i<seg.length; i++)
			{
				if (seg[i].length()>0)
				{
					blockAssign[Integer.valueOf(seg[i])]=numBlocks-1;
				}
			}
		}
		br.close();
	}
	
	protected void printParam()
	{
		super.printParam();
		param.printBlockParam("\t");
		IOUtil.println("\t#blocks: "+numBlocks);
	}
	
	public void initialize()
	{
		super.initialize();
		initBlocks();
	}
	
	public void initialize(String topicAssignFileName) throws IOException
	{
		super.initialize(topicAssignFileName);
		initBlocks();
	}
	
	protected void initBlocks()
	{
		if (numBlocks==0) return;
		blockTopicCounts=new int[numBlocks][param.numTopics];
		blockTokenCounts=new int[numBlocks];
		pi=new double[numBlocks][param.numTopics];
		for (int i=0; i<numDocs; i++)
		{
			LDADoc doc=corpus.get(i);
			int no=blockAssign[i];
			for (int topic=0; topic<param.numTopics; topic++)
			{
				blockTopicCounts[no][topic]+=doc.getTopicCount(topic);
				blockTokenCounts[no]+=doc.getTopicCount(topic);
			}
		}
	}
	
	public void sample(int numIters)
	{
		computeLogLikelihood();
		perplexity=Math.exp(-logLikelihood/numTestWords);
		if (param.verbose)
		{
			IOUtil.println("<0>"+"\tLog-LLD: "+format(logLikelihood)+"\tPPX: "+format(perplexity));
		}
		super.sample(numIters);
	}
	
	protected void sampleDoc(int doc)
	{
		int oldTopic,newTopic;
		int no=(numBlocks>0? blockAssign[doc] : -1);
		int interval=getSampleInterval();
		for (int token=0; token<corpus.get(doc).docLength(); token+=interval)
		{
			oldTopic=unassignTopic(doc, token);
			if (no!=-1)
			{
				blockTopicCounts[no][oldTopic]--;
				blockTokenCounts[no]--;
			}
			
			newTopic=sampleTopic(doc, token, oldTopic);
			
			assignTopic(doc, token, newTopic);
			if (no!=-1)
			{
				blockTopicCounts[no][newTopic]++;
				blockTokenCounts[no]++;
			}
		}
	}
	
	protected double topicUpdating(int doc, int topic, int vocab)
	{
		int no=(numBlocks>0? blockAssign[doc] : -1);
		double ratio=1.0/param.numTopics;
		if (no!=-1)
		{
			ratio=(blockTopicCounts[no][topic]+_alpha)/(blockTokenCounts[no]+_alpha*param.numTopics);
		}
		double score=0.0;
		if (type==TRAIN)
		{
			score=(param.alphaSum*ratio+corpus.get(doc).getTopicCount(topic))*
					(param.beta+topics[topic].getVocabCount(vocab))/
					(param.beta*param.numVocab+topics[topic].getTotalTokens());
		}
		else
		{
			score=(param.alphaSum*ratio+corpus.get(doc).getTopicCount(topic))*phi[topic][vocab];
		}
		return score;
	}
	
	protected void computeLogLikelihood()
	{
		computePi();
		super.computeLogLikelihood();
	}
	
	protected void computePi()
	{
		for (int block=0; block<numBlocks; block++)
		{
			for (int topic=0; topic<param.numTopics; topic++)
			{
				pi[block][topic]=(blockTopicCounts[block][topic]+_alpha)/
						(blockTokenCounts[block]+_alpha*param.numTopics);
			}
		}
	}
	
	protected void computeTheta()
	{
		for (int doc=0; doc<numDocs; doc++)
		{
			for (int topic=0; topic<param.numTopics; topic++)
			{
				int no=(numBlocks>0? blockAssign[doc] : -1);
				if (no!=-1)
				{
					theta[doc][topic]=(param.alphaSum*pi[no][topic]+corpus.get(doc).getTopicCount(topic))/
							(param.alphaSum+getSampleSize(corpus.get(doc).docLength()));
				}
				else
				{
					theta[doc][topic]=(param.alphaSum/param.numTopics+corpus.get(doc).getTopicCount(topic))/
							(param.alphaSum+getSampleSize(corpus.get(doc).docLength()));
				}
			}
		}
	}
	
	protected void initVariables()
	{
		super.initVariables();
		_alpha=param._alphaSum/param.numTopics;
	}
	
	public BPLDA(LDAParam parameters)
	{
		super(parameters);
	}
	
	public BPLDA(BPLDA LDATrain, LDAParam parameters)
	{
		super(LDATrain, parameters);
	}
	
	public BPLDA(String modelFileName, LDAParam parameters) throws IOException
	{
		super(modelFileName, parameters);
	}
	
	public static void main(String args[]) throws IOException
	{
		String seg[]=Thread.currentThread().getStackTrace()[1].getClassName().split("\\.");
		String modelName=seg[seg.length-1];
		LDAParam parameters=new LDAParam(LDACfg.vocabFileName);
		LDAResult trainResults=new LDAResult();
		LDAResult testResults=new LDAResult();
		
		BPLDA LDATrain=new BPLDA(parameters);
		LDATrain.readCorpus(LDACfg.trainCorpusFileName);
		LDATrain.readBlocks(LDACfg.trainClusterFileName);
		LDATrain.initialize();
		LDATrain.sample(LDACfg.numTrainIters);
		LDATrain.addResults(trainResults);
//		LDATrain.writeModel(LDACfg.getModelFileName(modelName));
		
		BPLDA LDATest=new BPLDA(LDATrain, parameters);
//		BPLDA LDATest=new BPLDA(LDACfg.getModelFileName(modelName), parameters);
		LDATest.readCorpus(LDACfg.testCorpusFileName);
		LDATest.initialize();
		LDATest.sample(LDACfg.numTestIters);
		LDATest.addResults(testResults);
		
		trainResults.printResults(modelName+" Training Perplexity:", LDAResult.PERPLEXITY);
		testResults.printResults(modelName+" Test Perplexity:", LDAResult.PERPLEXITY);
	}
}
