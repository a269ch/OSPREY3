package edu.duke.cs.osprey.gpu.cuda;

import jcuda.driver.CUcontext;
import jcuda.driver.CUdevice;
import jcuda.driver.CUdevice_attribute;
import jcuda.driver.JCudaDriver;

public class Gpu {
	
	private CUdevice device;
	private String name;
	private int[] computeVersion;
	private long memory;

	public Gpu(CUdevice device) {
		
		this.device = device;
		
		// get name
		byte[] bytes = new byte[1024];
		JCudaDriver.cuDeviceGetName(bytes, bytes.length, device);
		name = new String(bytes);
		
		// get memory
		long[] longs = new long[1];
		JCudaDriver.cuDeviceTotalMem(longs, device);
		memory = longs[0];
		
		// get attributes
		computeVersion = new int[] {
			getAttribute(CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MAJOR),
			getAttribute(CUdevice_attribute.CU_DEVICE_ATTRIBUTE_COMPUTE_CAPABILITY_MINOR)
		};
	}
	
	public CUdevice getDevice() {
		return device;
	}
	
	public int getAttribute(int attr) {
		int[] ints = new int[1];
		JCudaDriver.cuDeviceGetAttribute(ints, attr, device);
		return ints[0];
	}
	
	public String getName() {
		return name;
	}
	
	public long getMemory() {
		return memory;
	}
	
	public int[] getComputeVersion() {
		return computeVersion;
	}
	
	public boolean isComputeVersionAtLeast(int major, int minor) {
		
		if (computeVersion[0] < major) {
			return false;
		} else if (computeVersion[0] > major) {
			return true;
		}
		
		return computeVersion[1] >= minor; 
	}
	
	public boolean supportsDoubles() {
		return isComputeVersionAtLeast(1, 3);
	}
	
	public boolean supportsDynamicParallelism() {
		return isComputeVersionAtLeast(3, 5);
	}
	
	public CUcontext makeContextForThisThread() {
		CUcontext context = new CUcontext();
		JCudaDriver.cuCtxCreate(context, 0, device);
		return context;
	}
	
	public void waitForGpu() {
		JCudaDriver.cuCtxSynchronize();
	}
	
	@Override
	public String toString() {
		return getName();
	}
}
