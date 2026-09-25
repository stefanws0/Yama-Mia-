package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.ids.Role;
import java.util.List;
import java.util.Set;
import lombok.Value;

/** The candidates found for the report roles of one failed check. */
@Value
public class CheckCandidates
{
	String checkName;
	Set<Role> roles;
	List<Candidate> candidates;
}
