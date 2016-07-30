package DOM;


import java.awt.Frame;
import java.util.ArrayList;
import java.util.Arrays;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
//import ij.process.ShortProcessor;
import ij.process.ShortProcessor;
import ij.text.TextWindow;

public class SMLReconstruct {

	ImagePlus imp;
	/** stack with intermediate reconstruction for drift correction */
	ImageStack driftstack;
	/** stack containing cross correlation maps of intermediate reconstructions */
	ImageStack crosscorrstack;
	ImageStack zstack;
	ImageProcessor ip;
	//ResultsTable table;
	SMLDialog settings;
		
	
	/** x coordinates of particles in nm*/
	double [] x;
	/** y coordinates of particles in nm*/
	double [] y;
	double [] z_nm;
	double [] loc_errx;
	double [] loc_erry;
	/** frame numbers of particle*/
	double [] f;
	/** false positive mark */
	double [] fp;
	/** drift x coordinates between reconstructions */
	double [] driftx;
	/** drift y coordinates between reconstructions */
	double [] drifty;
	/** linearly interpolated drift x coordinates for each frame */
	double [] approxx;
	/** linearly interpolated drift x coordinates for each frame */
	double [] approxy;

	double max=0;
	double min=9999999;
	/** total number of frames in particle table */
	int nframes = 0;
	/** total number of particles in table */
	int nParticlesCount = 0;

	int new_width;
	int new_height;
	boolean bFrameSorted;

	/** threshold that determines what particles are used for reconstruction */
	double dFPThreshold; 
	
	
	//parameters of drift correction
	/** magnification of reconstructed images used for drift correction (average precision) */
	double dDriftMagn;
	/** width of reconstructed images used for drift correction */
	int drift_width;  
	/** height of reconstructed images used for drift correction */
	int drift_height; 
	/** number of intervals (and images) used for drift correction */
	int nIntervalsCount; 
	

	
	/**
	 * constructor for image reconstruction 
	 * @param title title of image
	 * @param sml_ corresponding analysis object
	 * @param dlg_  dialog
	 */
	
	SMLReconstruct(java.lang.String title, SMLAnalysis sml_, SMLDialog dlg_)
	{
		
		settings = dlg_;
		bFrameSorted = false;
		
		settings.dMagnification = 1.0/settings.dRecPixelSize;
		
		new_width  = (int) Math.ceil(settings.nRecWidth*settings.dMagnification);
		new_height = (int) Math.ceil(settings.nRecHeight*settings.dMagnification);

		imp = new ImagePlus();
		imp.setTitle("Reconstructed image");
		
		//threshold for particles reconstruction (include false positives or not)
		switch (dlg_.nRecParticles)
		{ //only true positives
			case 0:
				dFPThreshold = 0.3;
				break;
		 //all particles
			case 1:
				dFPThreshold = 1.1;
				break;
			default:
				dFPThreshold = 0.3;
				break;
		
		}
		
		//sort table by frame if required
		if(settings.bAveragePositions || settings.bDrift)
		{
			Sort_Results.sorting_external_silent(sml_, DOMConstants.Col_FrameN, true);
			bFrameSorted = true;
			sml_.showTable();
		}
		
		// load data
		sml_.ptable_lock.lock();
	
		//frame number
		f   = sml_.ptable.getColumnAsDoubles(DOMConstants.Col_FrameN);
		nParticlesCount = f.length;
		//coordinates
		x   = sml_.ptable.getColumnAsDoubles(DOMConstants.Col_Xnm);		
		y   = sml_.ptable.getColumnAsDoubles(DOMConstants.Col_Ynm);

		//calculate z-values if necessary
		if(settings.bCalculateZValues)
			calcZValues(sml_);
		
		z_nm = sml_.ptable.getColumnAsDoubles(DOMConstants.Col_Znm);
		
		if (settings.bTranslation)			
		{
			for (int i=0; i<nParticlesCount; i++)
			{
				x[i] += settings.dTranslationX;
				y[i] += settings.dTranslationY;
			}
		
		}
		//false positive mark
		fp  = sml_.ptable.getColumnAsDoubles(DOMConstants.Col_Fp);
		//localization precision
		loc_errx = sml_.ptable.getColumnAsDoubles(DOMConstants.Col_loc_errX);
		loc_erry = sml_.ptable.getColumnAsDoubles(DOMConstants.Col_loc_errY);
		
		sml_.ptable_lock.unlock();
		
		
		// find maximum frame number
		for (int n=0;n<f.length;n++)
		{
			if (f[n]>nframes) nframes=(int) f[n];
			
		}
	
	}
	
	/** Reconstruction drawing function used by the "Reconstruct Image" plugin.
	 *  Rather slow one, since it assumes that Results table is not sorted. 
	 * @param fstart show only particle after this frame
	 * @param fstop show only particle before this frame
	*/
	void draw_unsorted(int fstart, int fstop)
	{	
		FloatProcessor ipf = new FloatProcessor(new_width, new_height);
		double old_i, new_i, dErrx, dErry, xpeak, ypeak, loc_errxmag, loc_errymag;
		int xmag, ymag, xsq, ysq;
		int i,j;
		double dCutoff = 1000.0; //default cut-off is 1000 nm
		double dNorm = 1.0;
		double loc_errxlocal,loc_errylocal;
		
		
		if(settings.bCutoff)
			dCutoff = settings.dcutoff;


		IJ.showStatus("Reconstructing Image...");
		
		for (int n=0;n<nParticlesCount;n++)
		{
			if (f[n]>=fstart && f[n]<=fstop && fp[n]<dFPThreshold)
			{
				IJ.showProgress(n, nParticlesCount);
				xmag=(int) Math.round(x[n]*settings.dMagnification);
				ymag=(int) Math.round(y[n]*settings.dMagnification);
				

				if(loc_errx[n]<dCutoff && loc_erry[n]<dCutoff)
				{
					if(settings.nSDIndex>0)
					{
						loc_errxlocal  = settings.dFixedSD;
						loc_errylocal = settings.dFixedSD;
					}
					else
					{
						loc_errxlocal=loc_errx[n];
						loc_errylocal=loc_erry[n];
					}
					xsq = (int) Math.ceil(3*loc_errxlocal*settings.dMagnification);
					ysq = (int) Math.ceil(3*loc_errylocal*settings.dMagnification);
					xpeak=x[n]*settings.dMagnification;
					ypeak=y[n]*settings.dMagnification;
					loc_errxmag=loc_errxlocal*settings.dMagnification*1.41421356; //last number is just sqrt(2)
					loc_errymag=loc_errylocal*settings.dMagnification*1.41421356; //last number is just sqrt(2)

					///calculate area under pixelated gaussian
					dErrx = ErrorFunction.erf2((xmag-xsq-xpeak)/loc_errxmag) - ErrorFunction.erf2((1+xmag+xsq-xpeak)/loc_errxmag);
					dErry = ErrorFunction.erf2((ymag-ysq-ypeak)/loc_errymag) - ErrorFunction.erf2((1+ymag+ysq-ypeak)/loc_errymag);
					dNorm = 1/(dErrx*dErry);

					for(i=xmag-xsq;i<xmag+xsq+1;i++)
						for(j=ymag-ysq;j<ymag+ysq+1;j++)
						{
							if((i<new_width) && (j<new_height)&&(i>0)&&(j>0))
							{
								old_i=ipf.getf(i, j);
		
								dErrx = ErrorFunction.erf2((i-xpeak)/loc_errxmag) - ErrorFunction.erf2((1+i-xpeak)/loc_errxmag);
								dErry = ErrorFunction.erf2((j-ypeak)/loc_errymag) - ErrorFunction.erf2((1+j-ypeak)/loc_errymag);
								
								new_i = old_i + dNorm*dErrx*dErry;
								ipf.setf(i, j, (float)new_i);
							}
						}
				}		
			}
		}

		//imp.setProcessor(ipf.convertToShort(true));
		imp.setProcessor(ipf);
		IJ.run(imp, "Set Scale...", "distance=1 known="+settings.dRecPixelSize+" pixel=1 unit=nm");
		IJ.run(imp, "Enhance Contrast", "saturated=0.35");
	}
	
	/** Reconstruction drawing function used by the "Reconstruct Image" plugin.
	 *  Slow one since it assumes that Results table is not sorted. 
	 * @param fstart show only particle after this frame
	 * @param fstop show only particle before this frame
	 * @param fstep distance between z-slices
	 * @param 
	*/
	void draw_zstack(int fstart, int fstop, double zstep)
	{	
		double old_i, new_i, dErrx, dErry, xpeak, ypeak, loc_errxmag, loc_errymag;
		int xmag, ymag, xsq, ysq;
		int i,j;
		//double preG=0.25*Math.PI;
		double dCutoff = 1.0;
		double dNorm = 1.0;
		//double dVerif = 0.0;

		
		int nSlices;		//total number of slices
		int sliceNumber;	//slice number of particle
		double[] zValues;	//array with cloned z values
		double zmin, zmax;
		
		//create new array to sort without changing original
		zValues = z_nm.clone();
		Arrays.sort(zValues);
		//zmin = zValues[0];
		zmin = 0;
		zmax = zValues[zValues.length-1];
		
		
		/*DEBUGGING!*/
		zmin = 0;
		zmax = 800;
		/*DEBUGGING!*/
		
		nSlices = (int) Math.ceil((zmax-zmin)/zstep);
		//in case all z-values are zero still one slice has to be drawn
		if(nSlices==0)
			nSlices++;
		
		FloatProcessor[] ipf = new FloatProcessor[nSlices];
		
		for(int k = 0; k<nSlices; k++){
			ipf[k] = new FloatProcessor(new_width, new_height);
		}
		
		if(settings.bCutoff)
			dCutoff = settings.dcutoff;
		

		IJ.showStatus("Reconstructing Z-stack...");
		
		for (int n=0;n<nParticlesCount;n++)
		{
			if (f[n]>=fstart && f[n]<=fstop && fp[n]<dFPThreshold)
			{
				IJ.showProgress(n, nParticlesCount);
				xmag=(int) Math.round(x[n]*settings.dMagnification);
				ymag=(int) Math.round(y[n]*settings.dMagnification);
				
				//calculate sliceNumber for this particle
				sliceNumber = (int) Math.floor((z_nm[n]-zmin)/zstep);
				
				/*DEBUGGING*/
				if(sliceNumber>=nSlices)
					sliceNumber = nSlices-1;
				/*DEBUGGING*/
				
				if(loc_errx[n]<dCutoff && loc_erry[n]<dCutoff)
				{
					if(settings.nSDIndex>0)
					{
						loc_errx[n] = settings.dFixedSD;
						loc_erry[n] = settings.dFixedSD;
					}
					xsq = (int) Math.round(3*loc_errx[n]*settings.dMagnification);
					ysq = (int) Math.round(3*loc_erry[n]*settings.dMagnification);
					xpeak=x[n]*settings.dMagnification;
					ypeak=y[n]*settings.dMagnification;
					loc_errxmag=loc_errx[n]*settings.dMagnification*1.41421356; //last number is just sqrt(2)
					loc_errymag=loc_erry[n]*settings.dMagnification*1.41421356; //last number is just sqrt(2)
					//if(bNormalized)
					//{
					dErrx = ErrorFunction.erf2((xmag-xsq-xpeak)/loc_errxmag) - ErrorFunction.erf2((1+xmag+xsq-xpeak)/loc_errxmag);
					dErry = ErrorFunction.erf2((ymag-ysq-ypeak)/loc_errymag) - ErrorFunction.erf2((1+ymag+ysq-ypeak)/loc_errymag);
					dNorm = 1/(dErrx*dErry);
					//}
					//dVerif=0;
					for(i=xmag-xsq;i<xmag+xsq+1;i++)
						for(j=ymag-ysq;j<ymag+ysq+1;j++)
						{
							if((i<new_width) && (j<new_height)&&(i>0)&&(j>0))
							{
								old_i=ipf[sliceNumber].getf(i, j);
		
								dErrx = ErrorFunction.erf2((i-xpeak)/loc_errxmag) - ErrorFunction.erf2((1+i-xpeak)/loc_errxmag);
								dErry = ErrorFunction.erf2((j-ypeak)/loc_errymag) - ErrorFunction.erf2((1+j-ypeak)/loc_errymag);
								//if(bNormalized)
								//	{new_i = old_i + dNorm*dErrx*dErry;}
									//dVerif+=dNorm*dErrx*dErry;}
								//else
									//{new_i = old_i + preG*amp[n]*loc_errxmag*loc_errymag*dErrx*dErry;}
								new_i = old_i + dNorm*dErrx*dErry;
								ipf[sliceNumber].setf(i, j, (float)new_i);
							}
						}
				}		
			}
		}
		zstack = new ImageStack(new_width, new_height);
		
		for(int k=0; k<nSlices; k++){
			zstack.addSlice(null, ipf[k].convertToShort(true));
		}
		
		new ImagePlus("Z-stack (slices@"+zstep+"nm", zstack).show();

		//IJ.run(imp, "Set Scale...", "distance=1 known="+settings.dRecPixelSize+" pixel=1 unit=nm");
		//IJ.run(imp, "Enhance Contrast", "saturated=0.35");
	}
	
	/** Reconstruction drawing function used by the "Reconstruct Image" plugin during drift correction.
	 *  Faster one since it assumes that Results table is sorted by frame number. 
	 *  Puts resulting reconstructions to driftstack variable.
	 *  Uses different magnification determined for drift correction only
	 * @param fstart show only particle after this frame
	 * @param fstop show only particle before this frame
	*/
	void draw_sorted(int fstart, int fstop)
	{	
		FloatProcessor ipf = new FloatProcessor(drift_width, drift_height);
		double old_i, new_i, dErrx, dErry, xpeak, ypeak, loc_errxmag, loc_errymag;
		int xmag, ymag, xsq, ysq;
		int i,j,n;
		double loc_errxlocal,loc_errylocal;
	
		boolean bContinue = true;
		double dCutoff = 1000.0; //default cut-off is 1000 nm
		double dNorm = 1.0;
		
		if(settings.bCutoff)
			dCutoff = settings.dcutoff;
		
		n=0;
		while(f[n]<fstart)
			n++;
		
		while (bContinue) 
		{
			if (fp[n]<dFPThreshold)
			{
				
				xmag=(int) Math.round(x[n]*dDriftMagn);
				ymag=(int) Math.round(y[n]*dDriftMagn);
				
				if(loc_errx[n]<dCutoff && loc_erry[n]<dCutoff)
				{
					if(settings.nSDIndex>0)
					{
						loc_errxlocal  = settings.dFixedSD;
						loc_errylocal = settings.dFixedSD;
					}
					else
					{
						loc_errxlocal=loc_errx[n];
						loc_errylocal=loc_erry[n];
					}
					xsq = (int) Math.ceil(3*loc_errxlocal*dDriftMagn);
					ysq = (int) Math.ceil(3*loc_errylocal*dDriftMagn);
					xpeak=x[n]*dDriftMagn;
					ypeak=y[n]*dDriftMagn;
					loc_errxmag=loc_errxlocal*dDriftMagn*1.41421356; //number is just sqrt(2)
					loc_errymag=loc_errylocal*dDriftMagn*1.41421356; //number is just sqrt(2)
					//if(bNormalized)
					//{
					dErrx = ErrorFunction.erf2((xmag-xsq-xpeak)/loc_errxmag) - ErrorFunction.erf2((1+xmag+xsq-xpeak)/loc_errxmag);
					dErry = ErrorFunction.erf2((ymag-ysq-ypeak)/loc_errymag) - ErrorFunction.erf2((1+ymag+ysq-ypeak)/loc_errymag);
					dNorm = 1/(dErrx*dErry);
					//}					
					for(i=xmag-xsq;i<xmag+xsq+1;i++)
						for(j=ymag-ysq;j<ymag+ysq+1;j++)
						{
							if((i<drift_width) && (j<drift_height)&&(i>0)&&(j>0))
							{
								old_i=ipf.getf(i, j);
		
								dErrx = ErrorFunction.erf2((i-xpeak)/loc_errxmag) - ErrorFunction.erf2((1+i-xpeak)/loc_errxmag);
								dErry = ErrorFunction.erf2((j-ypeak)/loc_errymag) - ErrorFunction.erf2((1+j-ypeak)/loc_errymag);							
								new_i = old_i + dNorm*dErrx*dErry;
						
								ipf.setf(i, j, (float)new_i);
							}
						}
				}	
			}
			n++;
			if(n>=nParticlesCount)
				bContinue = false;
			else
			  if(f[n]>fstop)
				  bContinue = false;
		}

		
		//driftstack.addSlice(null, ipf.convertToShort(true));
		driftstack.addSlice(null, ipf);
		
	}
	
	/** Main function performing correlation based drift correction  */
	void DriftCorrection()
	{
				
		int i;

		int nIntervalFrames;
		int [] xymax;
		String sDriftData ="";
		TextWindow DriftTable; 
		
		//some variables for timing
		long startTime;
		long reconstructionTime=0;
		long fullTime;
		double [] driftxfull;
		double [] driftyfull;
		
		nIntervalFrames = settings.nDriftFrames;
		
		
		IJ.showStatus("Applying Drift correction...");


		dDriftMagn = 1.0/settings.nDriftScale;

		drift_width  = (int) (settings.nRecWidth*dDriftMagn);
		drift_height = (int) (settings.nRecHeight*dDriftMagn);
		driftstack = new ImageStack(drift_width, drift_height);
		crosscorrstack = new ImageStack(2*settings.nDriftPixels+1, 2*settings.nDriftPixels+1);
		
		//calculating number of time intervals
		if(settings.bFramesInterval)
			nIntervalsCount = (int) Math.floor((settings.nFrameMax-settings.nFrameMin+1)/((double)nIntervalFrames));
		else
			nIntervalsCount = (int) Math.floor(((double)nframes)/((double)nIntervalFrames));

		//sanity check
		if(settings.nDriftMaxDistnm<settings.nDriftScale)
		{
			IJ.error("Maximum drift displacement is smaller than pixel size, aborting!");
			return;
		}			
		
		if(nIntervalsCount <= 1)
		{
			IJ.error("Drift correction window is larger than total number of frames. Drift correction is cancelled.");
			return;
		}
	
		//the rest of the table is truncated for now (Baaaad!)			
		driftx = new double [nIntervalsCount];
		drifty = new double [nIntervalsCount];
		driftxfull =  new double [nframes];
		driftyfull =  new double [nframes];
		//let's start measuring time		
		startTime = System.nanoTime();
		
		//reconstructing images for cross-correlation calculation		
		IJ.showStatus("Applying Drift correction (images reconstruction)...");
		for (i=0; i<nIntervalsCount; i++)
		{
			IJ.showProgress(i, nIntervalsCount);
			if(settings.bFramesInterval)
				draw_sorted((int)settings.nFrameMin+i*nIntervalFrames, (int)settings.nFrameMin -1 +(i+1)*nIntervalFrames);
			else
				draw_sorted(i*nIntervalFrames+1, (i+1)*nIntervalFrames);			
		}
		IJ.showProgress(nIntervalsCount, nIntervalsCount);
		
		reconstructionTime = System.nanoTime() - startTime;
		IJ.log("Intermediate reconstructions time: " + String.format("%.2f",((double)Math.abs(reconstructionTime))*0.000000001) + " s");
		//show them, if asked
		if(settings.bShowIntermediate)
			new ImagePlus("Intermediate reconstructions (Drift frames="+settings.nDriftFrames+" max shift="+String.format("%d",((int)settings.nDriftMaxDistnm))+" nm)", driftstack).show();
		
		
		IJ.showStatus("Applying Drift correction (calculating cross-correlation)...");
		for (i=1; i<nIntervalsCount; i++)
		{
			IJ.showProgress(i-1, nIntervalsCount);			
			crosscorrstack.addSlice(null, crosscorrelation(driftstack.getProcessor(i),driftstack.getProcessor(i+1)));
		}
		IJ.showProgress(nIntervalsCount, nIntervalsCount);
		
		fullTime = System.nanoTime() - startTime;
		IJ.log("Cross correlation calculation time: " + String.format("%.2f", ((double)Math.abs(fullTime-reconstructionTime))*0.000000001)+ " s");
		IJ.log("Total time: " + String.format("%.2f",((double)Math.abs(fullTime))*0.000000001) + " s");

		if(settings.bShowCrossCorrelation)
			new ImagePlus("Cross Correlation (Drift frames="+settings.nDriftFrames+" max shift="+String.format("%d",((int)settings.nDriftMaxDistnm))+" nm)", crosscorrstack).show();
		
		driftx[0]=0;
		drifty[0]=0;
		
		//define pixel with highest cross correlation value on correlation map
		for (i=1; i<nIntervalsCount; i++)
		{
			xymax = getmaxpositions(crosscorrstack.getProcessor(i));
			driftx[i] = driftx[i-1]+((xymax[0]-settings.nDriftPixels)*settings.nDriftScale);
			drifty[i] = drifty[i-1]+((xymax[1]-settings.nDriftPixels)*settings.nDriftScale);
		}
		//applysimplecorrection();
		
		//linear interpolation of drift
		applylinearapproximationcorrection();		
		
		int lastDriftFrame=nIntervalsCount*nIntervalFrames;
		
		if(!settings.bFramesInterval)
		{
			//get results		
		
			for(i=0;i<lastDriftFrame;i++)
			{
				driftxfull[i]=approxx[i];
				driftyfull[i]=approxy[i];
			}
			
			//fill in the rest of correction with last value				
			if(nframes>lastDriftFrame)
			{
				for(i=lastDriftFrame;i<nframes;i++)
				{
					driftxfull[i]=approxx[lastDriftFrame-1];
					driftyfull[i]=approxy[lastDriftFrame-1];
				}
			}

		}
		else
		{
			//only corrected segment
			for(i=(int) settings.nFrameMin;i<=(int)(lastDriftFrame+settings.nFrameMin-1);i++)
			{
				driftxfull[i-1]=approxx[(int) (i-settings.nFrameMin)];
				driftyfull[i-1]=approxy[(int) (i-settings.nFrameMin)];				
			}
			//filling the rest with last value
			if(settings.nFrameMax>lastDriftFrame+settings.nFrameMin-1)
			{
				i=(int)(lastDriftFrame+settings.nFrameMin);
				while (i<=settings.nFrameMax)
				{
					driftxfull[i-1]=approxx[lastDriftFrame-1];
					driftyfull[i-1]=approxy[lastDriftFrame-1];				
					i++;
				}
				
			}

		}
		//show results of drift
		for(i=0;i<nframes;i++)
		{
			sDriftData = sDriftData + Integer.toString(i+1)+"\t"+Double.toString(driftxfull[i])+"\t"+Double.toString(driftyfull[i])+"\n";						
		}
		Frame frame = WindowManager.getFrame("Drift Correction (frames="+settings.nDriftFrames+" max shift="+String.format("%d",((int)settings.nDriftMaxDistnm))+" nm)");
		if (frame!=null && (frame instanceof TextWindow) )
		{
			DriftTable = (TextWindow)frame;
			DriftTable.getTextPanel().clear();
			DriftTable.getTextPanel().append(sDriftData);
			DriftTable.getTextPanel().updateDisplay();			
		}
			else
				DriftTable = new TextWindow("Drift Correction (frames="+settings.nDriftFrames+" max shift="+String.format("%d",((int)settings.nDriftMaxDistnm))+" nm)", "Frame_Number\tX_drift_(nm)\tY_drift_(nm)", sDriftData, 450, 300);			
		
		return;
		
	}
	
	void DriftUpdateResultTable(SMLAnalysis sml_, SMLDialog dlg_)
	{
		int i;
		//let's restore scale
		double pxscale =  sml_.ptable.getValueAsDouble(DOMConstants.Col_X, 0)/sml_.ptable.getValueAsDouble(DOMConstants.Col_Xnm, 0);
		
		//lock table
		sml_.ptable_lock.lock();
		for(i=0;i<nParticlesCount;i++)
		{
			sml_.ptable.setValue(DOMConstants.Col_Xnm, i, x[i]);
			sml_.ptable.setValue(DOMConstants.Col_Ynm, i, y[i]);
			sml_.ptable.setValue(DOMConstants.Col_X, i, x[i]*pxscale);
			sml_.ptable.setValue(DOMConstants.Col_Y, i, y[i]*pxscale);
		}
		sml_.ptable_lock.unlock();
		//sml_.ptable.updateResults();
		sml_.showTable();
	}

	void applysimplecorrection()
	{
		int i;
		int dIndex;
		int nIntervalFrames = settings.nDriftFrames;
		
		
		for(i=0;f[i]<nIntervalsCount*nIntervalFrames;i++)
		{
			dIndex = (int) Math.floor(((double)f[i])/((double)nIntervalFrames));
			x[i]+=driftx[dIndex];
			y[i]+=drifty[dIndex];
		}
			
		
	}
	/** function uses linear approximation on 
	 * discrete set of drift correction values
	 * */
	void applylinearapproximationcorrection()
	{
		int i;
		int dIndex;
		int nIntervalFrames = settings.nDriftFrames;
		double coeffx, coeffy;
		boolean bStop;
		
		int lastDriftFrame=nIntervalsCount*nIntervalFrames;
		
		approxx = new double [lastDriftFrame];
		approxy = new double [lastDriftFrame];
		
		
		//making linear approximation
		//first half interval
		for(i=0; i<nIntervalFrames*0.5; i++)
		{
			approxx[i] = 0.;
			approxy[i] = 0.;			
		}
		//middle part
		for(;i<nIntervalFrames*(nIntervalsCount-0.5); i++)
		{
			dIndex = (int) Math.floor((i-nIntervalFrames*0.5)/nIntervalFrames);
			coeffx = (driftx[dIndex+1]-driftx[dIndex])/nIntervalFrames;
			coeffy = (drifty[dIndex+1]-drifty[dIndex])/nIntervalFrames;
			approxx[i] = coeffx*(i-(dIndex+0.5)*nIntervalFrames) + driftx[dIndex];
			approxy[i] = coeffy*(i-(dIndex+0.5)*nIntervalFrames) + drifty[dIndex];			
			
		}
		dIndex = nIntervalsCount-1;
		//last segment
		for(;i<lastDriftFrame; i++)
		{
			approxx[i] = driftx[dIndex];
			approxy[i] = drifty[dIndex];		
		}
		
		//int lastDriftFrame=nIntervalsCount*nIntervalFrames;
		//for(i=0;i<lastDriftFrame;i++)
		
		
		if(settings.bFramesInterval)
		{
			i=0;
			//skip beginning
			while(f[i]<settings.nFrameMin)
				i++;
			bStop = false;
			while (!bStop)
			{
				dIndex = (int) f[i]-(int)settings.nFrameMin;
				x[i]+=approxx[dIndex];
				y[i]+=approxy[dIndex];
				
				i++;
				if(i>=nParticlesCount)
					bStop=true;
				else
				{
					//or the end of drift correction "clean" interval
					if(f[i]>=(settings.nFrameMin+nIntervalsCount*nIntervalFrames))
						bStop=true;
				}
			}
			//filling the rest
			if(settings.nFrameMax>settings.nFrameMin+nIntervalsCount*nIntervalFrames-1)
			{
				dIndex = nIntervalsCount-1;
				bStop = false;
				while (!bStop)
				{
					x[i]+=driftx[dIndex];
					y[i]+=drifty[dIndex];
					i++;
					if(i>=nParticlesCount)
						bStop=true;
					else
					{
						//or the end of drift correction "clean" interval
						if(f[i]>(settings.nFrameMax))
							bStop=true;
					}

				}
			}
		}
		else
		{
			
			bStop = false; 
			i=0;
			while (!bStop)
			{
				dIndex = (int) (f[i]-1);
				x[i]+=approxx[dIndex];
				y[i]+=approxy[dIndex];
				i++;
				//let's check whether we reached the end of all particles list
				if(i>=nParticlesCount)
					bStop=true;
				else
				{
					//or the end of drift correction "clean" interval
					if(f[i]>=(lastDriftFrame+1))
						bStop=true;
				}
				
			}

			//fill in the rest of correction with last value				
			if(nframes>lastDriftFrame)
			{
				dIndex = nIntervalsCount-1;
				for(;i<nParticlesCount;i++)
				{
					x[i]+=driftx[dIndex];
					y[i]+=drifty[dIndex];
				}
			}
			
		}
		return;
			
		
	}
	
	/** Function calculates cross-correlation between two images */	
	ShortProcessor crosscorrelation (ImageProcessor ip1, ImageProcessor ip2)
	{
		int nMaxPix = settings.nDriftPixels;
		int i, j, m, n;
		int tot;
		double dCC,dCC1,dCC2;
		double val1,val2;
		
		
		FloatProcessor resultIP;
		ShortProcessor returnIP;
		ImageProcessor extendedip1;
		
		
		tot = 2*nMaxPix+1;
		extendedip1 = new FloatProcessor(drift_width+tot-1, drift_height+tot-1);

		for (i=0;i<drift_width;i++)
			for (j=0;j<drift_height;j++)
				extendedip1.setf(i+nMaxPix, j+nMaxPix, ip1.get(i,j));

		resultIP = new FloatProcessor(tot, tot);
		
		
		for (i=0;i<tot;i++)
			for (j=0;j<tot;j++)
			{
				dCC=0;
				dCC1=0;
				dCC2=0;
				for(m=0; m<drift_width; m++)
					for(n=0; n<drift_height; n++)
					{
						val1=extendedip1.get(m+i,n+j);
						val2=ip2.get(m, n);
						dCC+=val1*val2;
						dCC1+=val1;
						dCC2+=val2;
					}
				if(dCC1!=0.0 && dCC2!=0.0)
					resultIP.setf(i,j,(float)(dCC/(dCC1*dCC2)));
				else
					resultIP.setf(i,j,0);
			}
		
		returnIP = (ShortProcessor) resultIP.convertToShort(true);
		returnIP.smooth();
		return returnIP;		
	}
	

	
	/** Sorts particles data by frame number, ascending. */	
	/*void sortbyframe()
	{
		int i, nSize;
		nSize = f.length;
	
		//put all data together in one array
		double [][] data = new double[nSize][6];
		for (i=0; i<nSize; i++)
		{
			data[i][0] = f[i];
			data[i][1] = x[i];
			data[i][2] = y[i];
			data[i][3] = loc_errx[i];
			data[i][4] = loc_erry[i];
			data[i][5] = fp[i];
		}
		//sort
		Arrays.sort(data, new tableComparator(0, true));
		//put data back
		for (i=0; i<nSize; i++)
		{
			  f[i] = data[i][0] ;
			  x[i] = data[i][1];
		  	  y[i] = data[i][2];
		  	  loc_errx[i] = data[i][3];
		  	  loc_erry[i] = data[i][4];
		  	  fp[i] = data[i][5];
		}		
		bFrameSorted = true;
	}*/
	
	/** Cleans the reconstruction viewer image. */
	void clear()
	{
	
		for (int i=0;i<ip.getWidth();i++)
			for (int j=0;j<ip.getHeight();j++)
				ip.set(i, j, 0);
	
	}
	/** Smoothes correlation images with simple gaussian filter */
	/*void convolvecorrelation()
	{
		GaussianBlur lowpassGauss = new GaussianBlur();
		for (int i=1; i<nIntervalsCount;i++)
		lowpassGauss.blurGaussian(crosscorrstack.getProcessor(i), 1.0, 1.0, 0.03);
		
		
	}*/
	
	/** Gets x and y coordinates of maximum intensity pixel on image. */	
	int [] getmaxpositions(ImageProcessor ipp)
	{
		int [] results = new int [2];
		results[0]=0;
		results[1]=0;

		int s = 0, smax=-10;
		
		for (int i=0;i<ipp.getWidth();i++)
		{
			for (int j=0;j<ipp.getHeight();j++)
			{
				s=ipp.get(i, j);	
				if (s>smax)
				{
					smax=s;
					results[0]=i;
					results[1]=j;
				}
			}
		}
		return results;		
	}
	
	/** Function averages localizations in consecutive frames within 1 pixel. */	
	void averagelocalizations(SMLAnalysis sml_)
	{
		int nUniqFrames,i,j;
		int nPatNumber = f.length;
		int nCount;
		int nCurrFrame, nFrameCount, nCurrentParticle, nCompareFrame, nCompareParticle,nFrameCompareCount;
		int nAveragesNumber;
		int [][] framesstat;
		double [] temp;
		double [][][] res_table_unique;
		double [][] res_table;	
		
		boolean bContinue;
		double dDistance;
		/** array containing accumulating values of averaged numbers*/
		ArrayList<double[]> Averaging_tbl = new ArrayList<double[]>();
		
		IJ.showStatus("Averaging single photoactivation events...");
		
		//sort table by frame number
		//should be already sorted in constructor

		sml_.ptable_lock.lock();

		res_table = new double [nPatNumber][DOMConstants.Col_Total_No_Tracks];
		
		//read results table to double array with transpose operation (makes later code easier)
		for (i=0;i<DOMConstants.Col_Total_No_Tracks;i++)
		{
			temp = sml_.ptable.getColumnAsDoubles(i);
			for(j=0;j<nPatNumber;j++)
				res_table[j][i]=temp[j];
		}			
		sml_.ptable_lock.unlock();			

		
		//calculate number of particles in each frame and
		//store it for each frame in framesstat array
		nUniqFrames = uniqFrames();
		framesstat = new int [nUniqFrames][2];
		nCurrFrame = (int) f[0];
		nCount = 0;
		nFrameCount = 0;
		framesstat[nFrameCount][0] = nCurrFrame;
		for (i=0;i<nPatNumber;i++)
		{
			if((int)f[i]==nCurrFrame)
				nCount++;
			else
			{	
				framesstat[nFrameCount][1]=nCount;
				nCurrFrame = (int)f[i];
				nFrameCount++;
				framesstat[nFrameCount][0]=nCurrFrame;
				nCount = 1;				
			}
			
		}
		//last element
		framesstat[nFrameCount][1]=nCount;
		framesstat[nFrameCount][0]=nCurrFrame;
		
		//resorting data to new arrays for convenience
				
		//1) allocating arrays with uneven rows 
		//  for particles from each frame separately
		
		res_table_unique = new double [nUniqFrames][][];
		
		for(i=0;i<nUniqFrames;i++)
		{
			nCount = framesstat[i][1];					
			res_table_unique[i] = new double [nCount][DOMConstants.Col_Total_No_Tracks];
		}
		
		//2) putting all data to those arrays
		nFrameCount = 0;
		nCurrentParticle = -1;
		for(nCount = 0; nCount<nPatNumber; nCount++)
		{
			nCurrentParticle++;
			if(nCurrentParticle == framesstat[nFrameCount][1])
			{
				nCurrentParticle=0;
				nFrameCount++;
				IJ.showProgress(nFrameCount, nUniqFrames);
			}
			res_table_unique[nFrameCount][nCurrentParticle]= res_table[nCount];
									
		}
		
		//all right!
		//now let's check single photoactivation events 
		nFrameCount = 0;
		nCurrentParticle = -1;
		for(nCount = 0; nCount<nPatNumber; nCount++)
		{
			nCurrentParticle++;
			if(nCurrentParticle == framesstat[nFrameCount][1])
			{
				nCurrentParticle=0;
				nFrameCount++;
			}
			
			if(res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Fp]<dFPThreshold && nFrameCount<(nUniqFrames-1))
			//if(fp_un[nFrameCount][nCurrentParticle]<dFPThreshold && nFrameCount<(nUniqFrames-1)) 
			{ 	
				nCurrFrame = framesstat[nFrameCount][0];
				//accumulating information				
				Averaging_tbl.clear();
				
				bContinue = true;
				nFrameCompareCount = nFrameCount+1;
				nCompareFrame = framesstat[nFrameCompareCount][0];
				nCompareParticle = -1;
				nAveragesNumber = 0;
				while(bContinue) 
				{
					nCompareParticle++;
					if(nCompareParticle == framesstat[nFrameCompareCount][1]) //end of particles list in current frame
					{
						if (nFrameCompareCount == nUniqFrames - 1)	//reached the end of total particle list, stop						
							{bContinue = false;}
						else
						{//jumping to next frame
						 // but let's check whether we found something while scanning previous one?
							//no, we didn't, stop scanning
							if(nCompareFrame-nCurrFrame>nAveragesNumber)
								{bContinue = false;}
							//yes, we did, let's jump then
							else
							{
								nFrameCompareCount++;
								nCompareFrame = framesstat[nFrameCompareCount][0];
								nCompareParticle = 0;
							}													
						}
							
					}
					//in the beginning look the next frame should be just after first
					if(nAveragesNumber==0 && (nCompareFrame-nCurrFrame>1.5))
						{bContinue = false;}
					//ok, let's see if all checks are passed
					
					if(bContinue && res_table_unique[nFrameCompareCount][nCompareParticle][DOMConstants.Col_Fp]<dFPThreshold)
					{//seems so. let's measure distances between molecules then
						dDistance = Math.sqrt(Math.pow(res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_X] -
													   res_table_unique[nFrameCompareCount][nCompareParticle][DOMConstants.Col_X], 2) 
								             +Math.pow(res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Y]-
								            		   res_table_unique[nFrameCompareCount][nCompareParticle][DOMConstants.Col_Y], 2)); 

						//they are closer then one pixel
						if(dDistance<1.0)
						{
							//found same photoactivation event
							//let's store values
							Averaging_tbl.add(res_table_unique[nFrameCompareCount][nCompareParticle]);
							nAveragesNumber++;
							//mark it as already used (fp)
							//debug stub
							res_table_unique[nFrameCompareCount][nCompareParticle][DOMConstants.Col_Fp]+=1.2;//mark it as an averaged one (>1)
							//exit from current frame (a bit lame one)
							nCompareParticle = framesstat[nFrameCompareCount][1] -1;
						}
					}
				}
				//was photoactivation event found?
				if(nAveragesNumber>0)
				{
					//adding particle itself to the list of averaged values
					Averaging_tbl.add(res_table_unique[nFrameCount][nCurrentParticle]);
					nAveragesNumber++;
					//calculating weighted average for this particle
					res_table_unique[nFrameCount][nCurrentParticle] = weightedAverage(Averaging_tbl);					
				}
			}//false positive check
		}
		//updating initial arrays used for rendering picture		
		nFrameCount = 0;
		nCurrentParticle = -1;
		for(nCount = 0; nCount<nPatNumber; nCount++)
		{
			nCurrentParticle++;
			if(nCurrentParticle == framesstat[nFrameCount][1])
			{
				nCurrentParticle=0;
				nFrameCount++;
			}
			//this one was used in averaging
			if(res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Fp]>1)
			{
				fp[nCount]+=1.2;
			}
			//this one was is the cumulative averaged particle
			if(res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Fp]<0)
			{				
				x[nCount]   = res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Xnm];
				y[nCount]   = res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Ynm];
				loc_errx[nCount] = res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_loc_errX];
				loc_erry[nCount] = res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_loc_errY];
				res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Fp]+=1.2;
				fp[nCount]  = res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Fp];
			}
		}
		
		///update results table
		if(settings.bUpdateAveragePositions)
		{
			sml_.ptable_lock.lock();
			//clear particle table
			sml_.ptable.reset();
			nFrameCount = 0;
			nCurrentParticle = -1;
			for(nCount = 0; nCount<nPatNumber; nCount++)
			{
				nCurrentParticle++;
				if(nCurrentParticle == framesstat[nFrameCount][1])
				{
					nCurrentParticle=0;
					nFrameCount++;
				}
				if(res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Fp]<=1)
				{
					sml_.ptable.incrementCounter();
					
					sml_.ptable.addValue("X_(px)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_X]);							
					sml_.ptable.addValue("Y_(px)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Y]);
					sml_.ptable.addValue("Frame Number", res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_FrameN]);
					sml_.ptable.addValue("X_(nm)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Xnm]);							
					sml_.ptable.addValue("X_loc_error(nm)", res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_loc_errX]);							
					sml_.ptable.addValue("Y_(nm)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Ynm]);
					sml_.ptable.addValue("Y_loc_error(nm)", res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_loc_errY]);
					sml_.ptable.addValue("Z_(nm)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Znm]);
					sml_.ptable.addValue("Z_loc_error(nm)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_loc_errZ]);							
					sml_.ptable.addValue("Amplitude_fit",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_AmplFit]);
					sml_.ptable.addValue("Amp_error",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Amp_error]);
					sml_.ptable.addValue("BGfit",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_BGfit]);
					sml_.ptable.addValue("BGfit_error",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_BGfit_error]);
					sml_.ptable.addValue("SD_X_(nm)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_SD_X]);
					sml_.ptable.addValue("SD_X_error(nm)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_SD_X_err]);
					sml_.ptable.addValue("SD_Y_(nm)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_SD_Y]);
					sml_.ptable.addValue("SD_Y_error(nm)",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_SD_Y_err]);
					sml_.ptable.addValue("False positive", res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_Fp]);							
					sml_.ptable.addValue("IntegratedInt",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_IntegrInt]);
					sml_.ptable.addValue("SNR", res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_SNR]);	
					//ptable.addValue("chi2_fit",chi2_fit);
					sml_.ptable.addValue("R2_fit",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_chi]);												
					//ptable.addValue("Iterations_fit",fdg.nIterations);
					sml_.ptable.addValue("Iterations_fit",res_table_unique[nFrameCount][nCurrentParticle][DOMConstants.Col_IterN]);
				}

			}
			
			sml_.ptable_lock.unlock();
			sml_.showTable();
		}
					
	}
	
	/** Function calculating weighted average of results
	 * */
	double [] weightedAverage(ArrayList<double[]> Averaging_tbl)
	{
		double [] dAverW;
		double dWeight;
		int nCount=0;
		dAverW = new double [DOMConstants.Col_Total_No_Tracks];
		
		//calculating averaged values
		for(double [] item:Averaging_tbl)
		{
			dWeight = Math.pow(item[DOMConstants.Col_loc_errX], -2);
			dAverW[DOMConstants.Col_X] += item[DOMConstants.Col_X]*dWeight; 				
			dAverW[DOMConstants.Col_Xnm] += item[DOMConstants.Col_Xnm]*dWeight;			
			dAverW[DOMConstants.Col_loc_errX] += dWeight;
			
			dWeight = Math.pow(item[DOMConstants.Col_loc_errY], -2);
			dAverW[DOMConstants.Col_Y] += item[DOMConstants.Col_Y]*dWeight;
			dAverW[DOMConstants.Col_Ynm] += item[DOMConstants.Col_Ynm]*dWeight;			
			dAverW[DOMConstants.Col_loc_errY] += dWeight;
			
			dWeight = Math.pow(item[DOMConstants.Col_Amp_error], -2);
			dAverW[DOMConstants.Col_AmplFit] += item[DOMConstants.Col_AmplFit]*dWeight;
			dAverW[DOMConstants.Col_Amp_error] += dWeight;
			
			dWeight = Math.pow(item[DOMConstants.Col_BGfit_error], -2);
			dAverW[DOMConstants.Col_BGfit] += item[DOMConstants.Col_BGfit]*dWeight;
			dAverW[DOMConstants.Col_BGfit_error] += dWeight;
			
			dWeight = Math.pow(item[DOMConstants.Col_SD_X_err], -2);
			dAverW[DOMConstants.Col_SD_X] += item[DOMConstants.Col_SD_X]*dWeight;
			dAverW[DOMConstants.Col_SD_X_err] += dWeight;
			
			dWeight = Math.pow(item[DOMConstants.Col_SD_Y_err], -2);
			dAverW[DOMConstants.Col_SD_Y] += item[DOMConstants.Col_SD_Y]*dWeight;
			dAverW[DOMConstants.Col_SD_Y_err] += dWeight;

			if(Math.abs(item[DOMConstants.Col_Znm])>0)
			{
				dWeight = Math.pow(item[DOMConstants.Col_loc_errZ], -2);
				dAverW[DOMConstants.Col_Znm] += item[DOMConstants.Col_Znm]*dWeight;
				dAverW[DOMConstants.Col_loc_errZ] += dWeight;
			}
			
			dAverW[DOMConstants.Col_IntegrInt] += item[DOMConstants.Col_IntegrInt];
			dAverW[DOMConstants.Col_SNR] += item[DOMConstants.Col_SNR];
			dAverW[DOMConstants.Col_chi] += item[DOMConstants.Col_chi];
			dAverW[DOMConstants.Col_IterN] += item[DOMConstants.Col_IterN];
			
			nCount++;
		}

		//weighted average sum divide by sum of weights 
		dAverW[DOMConstants.Col_X]/=dAverW[DOMConstants.Col_loc_errX];
		dAverW[DOMConstants.Col_Xnm]/=dAverW[DOMConstants.Col_loc_errX];
		//for the SD (SEM) itself it is this formula
		dAverW[DOMConstants.Col_loc_errX] = 1/Math.sqrt(dAverW[DOMConstants.Col_loc_errX]);
		
		dAverW[DOMConstants.Col_Y]/=dAverW[DOMConstants.Col_loc_errY];
		dAverW[DOMConstants.Col_Ynm]/=dAverW[DOMConstants.Col_loc_errY];
		dAverW[DOMConstants.Col_loc_errY] = 1/Math.sqrt(dAverW[DOMConstants.Col_loc_errY]);
		
		dAverW[DOMConstants.Col_AmplFit]/=dAverW[DOMConstants.Col_Amp_error];		
		dAverW[DOMConstants.Col_Amp_error] = 1/Math.sqrt(dAverW[DOMConstants.Col_Amp_error]);

		dAverW[DOMConstants.Col_BGfit]/=dAverW[DOMConstants.Col_BGfit_error];		
		dAverW[DOMConstants.Col_BGfit_error] = 1/Math.sqrt(dAverW[DOMConstants.Col_BGfit_error]);
		
		dAverW[DOMConstants.Col_SD_X]/=dAverW[DOMConstants.Col_SD_X_err];		
		dAverW[DOMConstants.Col_SD_X_err] = 1/Math.sqrt(dAverW[DOMConstants.Col_SD_X_err]);
		
		dAverW[DOMConstants.Col_SD_Y]/=dAverW[DOMConstants.Col_SD_Y_err];		
		dAverW[DOMConstants.Col_SD_Y_err] = 1/Math.sqrt(dAverW[DOMConstants.Col_SD_Y_err]);
		
		if(Math.abs(dAverW[DOMConstants.Col_Znm])>0)
		{
			dAverW[DOMConstants.Col_Znm]/=dAverW[DOMConstants.Col_loc_errZ];		
			dAverW[DOMConstants.Col_loc_errZ] = 1/Math.sqrt(dAverW[DOMConstants.Col_loc_errZ]);			
		}

		//just simple average for these values
		dAverW[DOMConstants.Col_IntegrInt] /= (double)nCount;
		dAverW[DOMConstants.Col_SNR] /= (double)nCount;
		dAverW[DOMConstants.Col_chi] /= (double)nCount;
		dAverW[DOMConstants.Col_IterN] /= (double)nCount;

		dAverW[DOMConstants.Col_Fp] = Averaging_tbl.get(nCount-1)[DOMConstants.Col_Fp]-1.2;
		dAverW[DOMConstants.Col_FrameN] = Averaging_tbl.get(nCount-1)[DOMConstants.Col_FrameN];

		return dAverW;
	}
	
	/** Function calculating number of unique frames */
	int uniqFrames()
	{
		int i, nCount=1, nLength = f.length;
		int nCurrFrame;
		boolean bContinue = true;
		
		i=0;
		nCurrFrame = (int)f[i];		
		while (bContinue)
		{
			i++;
			if(i==nLength)
				bContinue = false;
			else
			{
				if((int)f[i]!=nCurrFrame)
				{
					nCurrFrame=(int)f[i];
					nCount++;
					
				}
			}
			
		}
		
		return nCount;
		
	}


	/**function calculating z-values based on calibration curve*/
	
	void calcZValues(SMLAnalysis sml_)
	{
		double[] SDx = sml_.ptable.getColumnAsDoubles(15);
		double[] SDy = sml_.ptable.getColumnAsDoubles(16);
		
		//double SDx;
		//double SDy;
		
		double z;
		
		//calibration values, according to calibration curve: calValues[0]+calValues[1]*(SDx-SDy)+calValues[2]*(SDx-SDy)^2
		double[] calValues = {389.4,-101.3,-4.075};
		/*
		for(int i=0; i<x.length; i++){
			SDx = sml_.ptable.getValueAsDouble(15, i);
			SDy = sml_.ptable.getValueAsDouble(16, i);
			z = calValues[0]+calValues[1]*(SDx-SDy)+calValues[2]*(SDx-SDy)*(SDx-SDy);
			sml_.ptable.setValue(5,i, z);
		}
		*/
		
		for(int i=0; i<SDx.length; i++){
			z = calValues[0]+calValues[1]*(SDx[i]-SDy[i])+calValues[2]*(SDx[i]-SDy[i])*(SDx[i]-SDy[i]);
			if(z<0)
				z=0;
			sml_.ptable.setValue(5,i, z);
		}
		
		sml_.ptable.updateResults();
		sml_.ptable.show("Results");
	}

}