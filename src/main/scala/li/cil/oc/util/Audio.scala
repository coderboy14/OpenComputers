package li.cil.oc.util

import java.nio.ByteBuffer
import net.minecraft.client.Minecraft
import scala.collection.mutable
import org.lwjgl.openal.{Util, AL10}
import cpw.mods.fml.common.registry.TickRegistry
import cpw.mods.fml.relauncher.Side
import cpw.mods.fml.common.{TickType, ITickHandler}
import java.util
import org.lwjgl.BufferUtils

/**
 * This class contains the logic used by computers' internal "speakers".
 * It can generate square waves with a specific frequency and duration
 * and will play them through OpenAL, acquiring sources as necessary.
 * Tones that have finished playing are disposed automatically in the
 * tick handler.
 */
object Audio extends ITickHandler {
  private def sampleRate = 8000

  private val sources = mutable.Set.empty[Source]

  private def volume = Minecraft.getMinecraft.gameSettings.soundVolume

  def play(x: Float, y: Float, z: Float, frequencyInHz: Int, durationInMilliseconds: Int) {
    val distanceBasedGain = math.max(0, 1 - Minecraft.getMinecraft.thePlayer.getDistance(x, y, z) / 12).toFloat
    val gain = distanceBasedGain * volume
    if (gain > 0) {
      val sampleCount = durationInMilliseconds * sampleRate / 1000
      val data = BufferUtils.createByteBuffer(sampleCount)
      val step = frequencyInHz / sampleRate.toFloat
      var offset = 0f
      for (sample <- 0 until sampleCount) {
        val angle = 2 * math.Pi * offset
        // We could sort of fake the square wave with a little less
        // computational effort, but until somebody complains let's
        // go with the fourier series! We leave out the  4 / Pi because
        // it's just an approximation and we avoid clipping like this.
        val value = (0 to 6).map(k => math.sin((1 + k * 2) * angle) / (1 + k * 2)).sum * Byte.MaxValue
        // val tmp = math.sin(angle) * Byte.MaxValue
        // val value = math.signum(tmp) * 0.99 + tmp * 0.01
        offset += step
        if (offset > 1) offset -= 1
        data.put(value.toByte)
      }
      data.rewind()

      sources.synchronized(sources += new Source(x, y, z, data, gain))
    }
  }

  def update() {
    sources.synchronized(sources --= sources.filter(_.checkFinished))

    // Clear error stack.
    AL10.alGetError()
  }

  private class Source(val x: Float, y: Float, z: Float, val data: ByteBuffer, val gain: Float) {
    // Clear error stack.
    AL10.alGetError()

    val source = BufferUtils.createIntBuffer(1)

    val buffer = BufferUtils.createIntBuffer(1)

    AL10.alGenBuffers(buffer)
    Util.checkALError()

    try {
      AL10.alBufferData(buffer.get(0), AL10.AL_FORMAT_MONO8, data, sampleRate)
      Util.checkALError()

      AL10.alGenSources(source)
      Util.checkALError()

      try {
        AL10.alSourceQueueBuffers(source.get(0), buffer)
        Util.checkALError()

        AL10.alSource3f(source.get(0), AL10.AL_POSITION, x, y, z)
        AL10.alSourcef(source.get(0), AL10.AL_GAIN, gain * 0.3f)
        Util.checkALError()

        AL10.alSourcePlay(source.get(0))
        Util.checkALError()
      }
      catch {
        case t: Throwable =>
          AL10.alDeleteSources(source)
          throw t
      }
    }
    catch {
      case t: Throwable =>
        AL10.alDeleteBuffers(buffer)
        throw t
    }

    def checkFinished = AL10.alGetSourcei(source.get(0), AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING && {
      AL10.alDeleteSources(source)
      AL10.alDeleteBuffers(buffer)
      true
    }
  }

  TickRegistry.registerTickHandler(this, Side.CLIENT)

  override def getLabel = "OpenComputers - Audio"

  override def ticks = util.EnumSet.of(TickType.CLIENT)

  override def tickStart(`type`: util.EnumSet[TickType], tickData: AnyRef*) {}

  override def tickEnd(`type`: util.EnumSet[TickType], tickData: AnyRef*) {
    update()
  }
}
