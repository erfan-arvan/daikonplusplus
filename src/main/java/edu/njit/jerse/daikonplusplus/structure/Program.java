package edu.njit.jerse.daikonplusplus.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Represents a complete Java program or compilation unit under analysis. Contains top-level program
 * structure elements such as classes or interfaces.
 */
public class Program {

  private final List<ProgramStructureElement> topLevelElements;
  private final String name;

  public Program(String name) {
    this.name = name;
    this.topLevelElements = new ArrayList<>();
  }

  public void addTopLevelElement(ProgramStructureElement element) {
    topLevelElements.add(element);
  }

  public List<ProgramStructureElement> getTopLevelElements() {
    return Collections.unmodifiableList(topLevelElements);
  }

  public String getName() {
    return name;
  }

  public Optional<ProgramStructureElement> findByUniqueId(String uniqueId) {
    for (ProgramStructureElement element : topLevelElements) {
      Optional<ProgramStructureElement> match = findRecursive(element, uniqueId);
      if (match.isPresent()) return match;
    }
    return Optional.empty();
  }

  private Optional<ProgramStructureElement> findRecursive(
      ProgramStructureElement current, String uniqueId) {
    if (current.getUniqueId().equals(uniqueId)) {
      return Optional.of(current);
    }
    for (ProgramStructureElement child : current.getChildren()) {
      Optional<ProgramStructureElement> result = findRecursive(child, uniqueId);
      if (result.isPresent()) return result;
    }
    return Optional.empty();
  }

  public List<ProgramStructureElement> getAllElements() {
    List<ProgramStructureElement> all = new ArrayList<>();
    for (ProgramStructureElement top : topLevelElements) {
      collectRecursive(top, all);
    }
    return all;
  }

  private void collectRecursive(
      ProgramStructureElement current, List<ProgramStructureElement> acc) {
    acc.add(current);
    for (ProgramStructureElement child : current.getChildren()) {
      collectRecursive(child, acc);
    }
  }

  // ==========================
  //  Factory Methods
  // ==========================

}
